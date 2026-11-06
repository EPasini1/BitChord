#include "window_jni.cpp"

#include <iostream>
#include <stdexcept>

namespace {

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

template <typename Interface>
class FakeCom : public Interface {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void** out) override {
        if (out == nullptr) return E_POINTER;
        *out = nullptr;
        if (id != __uuidof(IUnknown) && id != __uuidof(Interface)) return E_NOINTERFACE;
        *out = static_cast<Interface*>(this);
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
};

enum class FailureStage { None, Padding, Buffer, Submission };

struct StreamFailure {
    FailureStage stage = FailureStage::None;
    HRESULT status = AUDCLNT_E_DEVICE_INVALIDATED;
    unsigned after = 0;
    unsigned calls = 0;

    HRESULT at(FailureStage current) {
        if (stage != current) return S_OK;
        ++calls;
        if (calls <= after) return S_OK;
        // Let the old infinite loop fail rather than hanging the regression runner.
        return calls > 64 ? E_FAIL : status;
    }
};

class FakeRender final : public FakeCom<IAudioRenderClient> {
public:
    explicit FakeRender(StreamFailure& failure) : failure(failure) {}

    HRESULT STDMETHODCALLTYPE GetBuffer(UINT32 frames, BYTE** out) override {
        const HRESULT result = failure.at(FailureStage::Buffer);
        if (FAILED(result)) return result;
        buffer.resize(static_cast<size_t>(frames) * 2);
        *out = reinterpret_cast<BYTE*>(buffer.data());
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE ReleaseBuffer(UINT32 frames, DWORD) override {
        const HRESULT result = failure.at(FailureStage::Submission);
        if (SUCCEEDED(result)) submitted += frames;
        return result;
    }

    StreamFailure& failure;
    std::vector<float> buffer;
    unsigned submitted = 0;
};

class FakeClient final : public FakeCom<IAudioClient> {
public:
    FakeClient() : render(failure) {}

    HRESULT STDMETHODCALLTYPE Initialize(
        AUDCLNT_SHAREMODE, DWORD, REFERENCE_TIME, REFERENCE_TIME,
        const WAVEFORMATEX*, LPCGUID) override {
        return initializeResult;
    }
    HRESULT STDMETHODCALLTYPE GetBufferSize(UINT32* frames) override {
        *frames = bufferFrames;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetStreamLatency(REFERENCE_TIME*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetCurrentPadding(UINT32* frames) override {
        *frames = 0;
        return failure.at(FailureStage::Padding);
    }
    HRESULT STDMETHODCALLTYPE IsFormatSupported(
        AUDCLNT_SHAREMODE, const WAVEFORMATEX*, WAVEFORMATEX**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetMixFormat(WAVEFORMATEX**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetDevicePeriod(REFERENCE_TIME*, REFERENCE_TIME*) override {
        return E_NOTIMPL;
    }
    HRESULT STDMETHODCALLTYPE Start() override { ++starts; return S_OK; }
    HRESULT STDMETHODCALLTYPE Stop() override { ++stops; return S_OK; }
    HRESULT STDMETHODCALLTYPE Reset() override { return S_OK; }
    HRESULT STDMETHODCALLTYPE SetEventHandle(HANDLE) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetService(REFIID id, void** out) override {
        *out = nullptr;
        if (id != __uuidof(IAudioRenderClient)) return E_NOINTERFACE;
        *out = static_cast<IAudioRenderClient*>(&render);
        return S_OK;
    }

    StreamFailure failure;
    FakeRender render;
    UINT32 bufferFrames = 64;
    HRESULT initializeResult = S_OK;
    unsigned starts = 0;
    unsigned stops = 0;
};

class FakeDevice final : public FakeCom<IMMDevice> {
public:
    FakeDevice(FakeClient& first, FakeClient& next) : first(first), next(next) {}

    HRESULT STDMETHODCALLTYPE Activate(REFIID id, DWORD, PROPVARIANT*, void** out) override {
        *out = nullptr;
        if (id != __uuidof(IAudioClient)) return E_NOINTERFACE;
        *out = static_cast<IAudioClient*>(activations++ == 0 ? &first : &next);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OpenPropertyStore(DWORD, IPropertyStore**) override {
        return E_NOTIMPL;
    }
    HRESULT STDMETHODCALLTYPE GetId(LPWSTR* out) override {
        const size_t bytes = (id.size() + 1) * sizeof(wchar_t);
        *out = static_cast<LPWSTR>(CoTaskMemAlloc(bytes));
        if (*out == nullptr) return E_OUTOFMEMORY;
        std::memcpy(*out, id.c_str(), bytes);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetState(DWORD* state) override {
        *state = DEVICE_STATE_ACTIVE;
        return S_OK;
    }

    FakeClient& first;
    FakeClient& next;
    std::wstring id = L"test-endpoint";
    unsigned activations = 0;
};

class FakeEnumerator final : public FakeCom<IMMDeviceEnumerator> {
public:
    explicit FakeEnumerator(FakeDevice& device) : device(device) {}

    HRESULT STDMETHODCALLTYPE EnumAudioEndpoints(
        EDataFlow, DWORD, IMMDeviceCollection**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetDefaultAudioEndpoint(EDataFlow, ERole, IMMDevice** out) override {
        *out = &device;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetDevice(LPCWSTR id, IMMDevice** out) override {
        *out = nullptr;
        if (device.id != id) return HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
        *out = &device;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE RegisterEndpointNotificationCallback(IMMNotificationClient*) override {
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE UnregisterEndpointNotificationCallback(IMMNotificationClient*) override {
        return S_OK;
    }

    FakeDevice& device;
};

struct Fixture {
    FakeClient first;
    FakeClient next;
    FakeDevice device{first, next};
    FakeEnumerator enumerator{device};
    std::vector<float> samples = std::vector<float>(8, 0.25f);

    Fixture() { g_audio_enumerator = &enumerator; }
    ~Fixture() {
        release_audio_locked();
        g_audio_enumerator = nullptr;
    }

    void open() {
        require(open_audio(L"", 48'000, 2, 4, true) > 0, "fake endpoint did not open");
    }
    int write() {
        return write_audio(samples.data(), static_cast<int>(samples.size()), 1.0f);
    }
    int expectedBytes() const { return static_cast<int>(samples.size() * sizeof(float)); }
};

void stable_route_keeps_stream() {
    Fixture fixture;
    fixture.open();
    g_audio_reroute = true;
    require(fixture.write() == fixture.expectedBytes(), "stable endpoint lost samples");
    require(fixture.device.activations == 1, "a default-role notification reopened a healthy stream");
}

void changed_endpoint_reopens_stream() {
    Fixture fixture;
    fixture.open();
    fixture.device.id = L"replacement-endpoint";
    g_audio_reroute = true;
    require(fixture.write() == fixture.expectedBytes(), "changed endpoint lost samples");
    require(fixture.device.activations == 2, "changed endpoint was not opened");
    require(fixture.first.stops == 1, "previous endpoint was not stopped");
}

void invalidation_recovers(FailureStage stage, HRESULT status = AUDCLNT_E_DEVICE_INVALIDATED) {
    Fixture fixture;
    fixture.first.failure.stage = stage;
    fixture.first.failure.status = status;
    fixture.open();
    require(fixture.write() == fixture.expectedBytes(), "invalidated same-endpoint stream did not recover");
    require(fixture.device.activations == 2, "invalidation reused the dead client");
    require(fixture.first.stops == 1, "invalidated client was not stopped");
    require(fixture.next.starts == 1, "replacement client did not start");
    require(fixture.next.render.submitted == 4, "replacement client did not receive all frames");
    require(g_audio_error.empty(), "a successful recovery left a stale error");
}

void invalidated_padding_recovers() { invalidation_recovers(FailureStage::Padding); }
void invalidated_buffer_recovers() { invalidation_recovers(FailureStage::Buffer); }
void invalidated_submission_recovers() { invalidation_recovers(FailureStage::Submission); }

void invalidated_resources_recover() {
    for (const auto stage : {FailureStage::Padding, FailureStage::Buffer, FailureStage::Submission}) {
        invalidation_recovers(stage, AUDCLNT_E_RESOURCES_INVALIDATED);
    }
}

void repeated_invalidation_is_bounded() {
    for (const auto stage : {FailureStage::Padding, FailureStage::Buffer, FailureStage::Submission}) {
        Fixture fixture;
        fixture.first.failure.stage = stage;
        fixture.next.failure.stage = stage;
        fixture.open();
        require(fixture.write() == 0, "a failed write claimed to have submitted samples");
        require(fixture.device.activations > 1 && fixture.device.activations <= 4,
                "recovery must reopen the client, with at most three retries per block");
        require(fixture.first.failure.calls + fixture.next.failure.calls < 16,
                "recovery kept spinning on an invalidated stream");
        require(!g_audio_error.empty(), "exhausted recovery did not report its error");
        const bool unlocked = g_audio_mutex.try_lock();
        if (unlocked) g_audio_mutex.unlock();
        require(unlocked, "failed write kept the audio mutex locked");
    }
}

void partial_write_preserves_playhead() {
    Fixture fixture;
    fixture.first.bufferFrames = 2;
    fixture.first.failure.stage = FailureStage::Buffer;
    fixture.first.failure.after = 1;
    fixture.open();
    require(fixture.write() == fixture.expectedBytes(), "recovery lost the remainder of a partial block");
    require(fixture.first.render.submitted == 2, "unexpected frames on the old stream");
    require(fixture.next.render.submitted == 2, "unexpected frames on the replacement stream");
    require(played_frames_locked() == 4, "recovery reset or double-counted the playhead");
}

void rebuild_failure_is_reported() {
    Fixture fixture;
    fixture.first.failure.stage = FailureStage::Padding;
    fixture.next.initializeResult = E_FAIL;
    fixture.open();
    fixture.write();
    require(fixture.device.activations > 1 && fixture.device.activations <= 3,
            "failed initialization was retried without a bound");
    require(g_audio_error.find(L"Starting WASAPI shared mode") != std::wstring::npos,
            "replacement initialization failure was not preserved");
}

void paused_rebuild_does_not_start_playback() {
    Fixture fixture;
    fixture.open();
    g_audio_started = false;
    fixture.device.id = L"replacement-endpoint";
    reroute_locked();
    require(fixture.device.activations == 2, "paused stream was not rebuilt");
    require(fixture.next.starts == 0, "rebuilding a paused stream started playback");
}

}  // namespace

int main() {
    struct Case { const char* name; void (*run)(); };
    const Case cases[] = {
        {"stable route", stable_route_keeps_stream},
        {"changed endpoint", changed_endpoint_reopens_stream},
        {"invalidated padding", invalidated_padding_recovers},
        {"invalidated buffer", invalidated_buffer_recovers},
        {"invalidated submission", invalidated_submission_recovers},
        {"invalidated resources", invalidated_resources_recover},
        {"bounded retries", repeated_invalidation_is_bounded},
        {"partial write playhead", partial_write_preserves_playhead},
        {"rebuild failure", rebuild_failure_is_reported},
        {"paused rebuild", paused_rebuild_does_not_start_playback},
    };
    unsigned failed = 0;
    for (const auto& test : cases) {
        try {
            test.run();
            std::cout << "PASS: " << test.name << '\n';
        } catch (const std::exception& failure) {
            ++failed;
            std::cerr << "FAIL: " << test.name << ": " << failure.what() << '\n';
        }
    }
    return failed == 0 ? 0 : 1;
}
