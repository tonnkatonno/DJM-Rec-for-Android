#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <oboe/Oboe.h>

#include "AlsaPcmAudioSource.h"
#include "RingBuffer.h"
#include "UsbIsoAudioSource.h"
#include "WaveformAnalyzer.h"
#include "writers/AudioWriter.h"

namespace djmrec {

enum class ContainerFormat : int {
    Wav = 0,
    Flac = 1
};

/**
 * Which producer is currently feeding mRingBuffer. Oboe is the default AAudio/AudioRecord
 * path (used for plain stereo UAC2 devices); UsbIso is the libusb raw-isochronous path used
 * to reach into a multichannel interface for a specific channel pair that AAudio itself has
 * no way to select.
 */
enum class SourceMode { None, Oboe, UsbIso, RootAlsa };

/**
 * The whole native audio pipeline in one place:
 *
 *   AAudio exclusive MMAP callback (producer, realtime)
 *        -> RingBuffer (lock-free hand-off)
 *        -> encoder thread (consumer, THREAD_PRIORITY_URGENT_AUDIO on the Kotlin side owns
 *           the *service* thread priority; this native thread inherits pthread defaults and
 *           is deliberately NOT realtime since file I/O/encoding must be free to block)
 *        -> AudioWriter (WAV/FLAC)
 *
 * Exactly one recording session is supported at a time, matching the app's single-mixer,
 * single-session use case.
 */
class UsbAudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static UsbAudioEngine& instance();

    /** Opens the exclusive AAudio input stream. Returns the negotiated sample rate, or -1. */
    int open(int32_t audioManagerDeviceId, int32_t sampleRateHint, int32_t channelCount, int32_t bitDepthHint);

    /**
     * Opens the raw libusb isochronous capture path instead of AAudio, extracting a stereo
     * pair out of a wider multichannel USB Audio interface. The source briefly measures actual frame cadence
     * before returning, which covers UAC2 devices that do not answer clock-frequency queries.
     * Returns the measured sample rate on success, or -1 on failure.
     */
    int openUsbIso(const UsbIsoAudioSource::Config& isoConfig, int32_t sampleRateHint);
    int openRootAlsa(const AlsaPcmAudioSource::Config& alsaConfig);

    bool startRecording(const std::string& path, ContainerFormat format);
    void pauseRecording();
    void resumeRecording();
    /** Stops encoding, finalizes the file, and returns total recorded duration in ms. */
    int64_t stopRecording();
    void closeEngine();

    /** [leftPeakDb, leftRmsDb, rightPeakDb, rightRmsDb] — safe to call from any thread. */
    void getLevels(float outLevels[4]) const;
    bool isClipping() const;
    int64_t getElapsedMillis() const;
    /** Sample rate of the currently open capture stream, or 0 if none is open -- lets dependent
     *  pipelines (e.g. the live-stream AAC encoder) configure themselves to the actual negotiated
     *  rate instead of assuming a fixed value. */
    int32_t getCurrentSampleRate() const { return mFormat.sampleRate; }
    int32_t getXRunCount() const;
    void getUsbIsoTransferStats(uint64_t outStats[7]) const;
    std::string getDiagnosticSummary();

    /** Copies the RGB waveform snapshot into @p outBins (kBinCount * 4 floats).
     *  Safe to call from any thread. */
    void getWaveformBins(float* outBins) const;
    void setWaveformEnabled(bool enabled);
    static constexpr int kWaveformBinCount = WaveformAnalyzer::kBinCount;

    /** Linear input gain applied to every captured sample (all capture paths), before metering
     *  and before it reaches the ring buffer/encoder -- so the VU meter and the recorded file
     *  always agree on what "the signal" is. @p db is clamped to a sane range internally. */
    void setGainDb(float db);

    /**
     * Live headphone/output monitoring of the (gained) captured signal, independent of
     * recording -- lets the user *hear* crackle/clipping in real time to judge gain staging,
     * not just watch the VU meter. Opens a second, low-latency Oboe output stream fed from a
     * small dedicated ring buffer; standard Android output routing applies (wired/BT headset if
     * connected, phone speaker otherwise). Safe to toggle at any time, including mid-recording
     * and before any capture stream is open (takes effect once one opens).
     */
    void setMonitoringEnabled(bool enabled);
    bool isMonitoringEnabled() const { return mMonitoringEnabled.load(std::memory_order_relaxed); }

    /**
     * Enables/disables a dedicated ring buffer feeding the live-stream (Icecast) encoder path,
     * independent of recording-to-file and of monitoring -- same on/off shape as
     * [setMonitoringEnabled], but with no output audio stream of its own: this only ever
     * produces PCM for [pullStreamPcm16] to pull, nothing is played back or sent anywhere by
     * this class itself. Takes effect immediately if a capture stream is already open, or on
     * the next open*() call otherwise (mirrors monitoring's retry-on-open behavior).
     */
    void setStreamingEnabled(bool enabled);
    bool isStreamingEnabled() const { return mStreamingEnabled.load(std::memory_order_relaxed); }

    /**
     * Consumer-side pull for the live-stream path: copies up to @p maxFrames interleaved
     * stereo 16-bit PCM frames (downconverted from the internal I32 canonical format at write
     * time) into @p outInterleavedS16Stereo. Returns the number of frames actually copied (0 if
     * streaming is disabled, no capture stream is open yet, or the buffer is momentarily
     * empty). Single-consumer like mRingBuffer's encoder thread -- safe to call from one
     * dedicated thread/coroutine, not safe to call concurrently from more than one.
     */
    size_t pullStreamPcm16(int16_t* outInterleavedS16Stereo, size_t maxFrames);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    UsbAudioEngine() = default;

    void encoderThreadLoop();
    static size_t bytesPerFrameFor(oboe::AudioFormat format, int32_t channelCount);

    /** Shared tail of both capture paths once a canonical stereo I32 frame batch is in hand:
     *  updates the VU meter atomics and (if recording) writes into mRingBuffer. Called from
     *  the libusb event thread by UsbIsoAudioSource's callback. */
    void onUsbIsoFrames(const int32_t* interleavedStereo, size_t frameCount);

    /** Multiplies @p samples in place by the current gain and hard-clamps to int32 range.
     *  A hard clamp (not a limiter) is deliberate: the point of exposing gain + live monitoring
     *  is to let the user *hear* when they've pushed too far, the same way clipping on the
     *  mixer's own meters is a real, audible signal -- silently limiting it away would hide
     *  exactly what they're trying to check for. */
    void applyGain(int32_t* samples, size_t sampleCount) const;

    /** Feeds @p interleavedStereo (already gained) into the monitor ring buffer if monitoring
     *  is currently enabled. Non-blocking (try_lock) -- called from realtime capture threads. */
    void feedMonitor(const int32_t* interleavedStereo, size_t frameCount);

    /** Feeds @p interleavedStereo (already gained) into the streaming ring buffer, downconverted
     *  to I16 on the way in, if streaming is currently enabled. Non-blocking (try_lock) --
     *  called from realtime capture threads, same as feedMonitor. */
    void feedStream(const int32_t* interleavedStereo, size_t frameCount);

    /** (Re)allocates mStreamRingBuffer once mFormat.sampleRate is finalized, if streaming was
     *  enabled before any capture stream was open -- mirrors startMonitorStreamIfEnabled(). Must
     *  be called while holding mControlMutex. */
    void startStreamingRingBufferIfEnabled();

    /** (Re)opens mMonitorStream at mFormat.sampleRate if monitoring is enabled -- called once a
     *  capture stream's format is finalized (initial open, or after mFormat changes) so a
     *  previously-enabled monitor keeps working across capture-source restarts. Must be called
     *  while holding mControlMutex. */
    void startMonitorStreamIfEnabled();

    /** Tears down mMonitorStream + mMonitorRingBuffer without touching mMonitoringEnabled (the
     *  user's on/off preference persists across this, same as mWaveformEnabled). Must be called
     *  while holding mControlMutex. Safe to call whether or not a monitor stream is open. */
    void stopMonitorStream();

    /** oboe::AudioStreamDataCallback for mMonitorStream specifically (disambiguated from the
     *  capture stream by pointer identity in onAudioReady). Pulls from the monitor ring buffer,
     *  padding with silence on underrun rather than glitching. */
    oboe::DataCallbackResult onMonitorAudioReady(void* audioData, int32_t numFrames);

    std::shared_ptr<oboe::AudioStream> mStream;
    std::unique_ptr<UsbIsoAudioSource> mUsbIsoSource;
    std::unique_ptr<AlsaPcmAudioSource> mAlsaSource;
    SourceMode mSourceMode = SourceMode::None;
    std::unique_ptr<RingBuffer> mRingBuffer;
    std::unique_ptr<AudioWriter> mWriter;
    std::unique_ptr<WaveformAnalyzer> mWaveformAnalyzer;
    std::thread mEncoderThread;

    std::mutex mControlMutex; // guards start/stop/pause transitions (not the realtime path)
    std::atomic<bool> mStreamOpen{false};
    std::atomic<bool> mRecording{false};
    std::atomic<bool> mPaused{false};
    std::atomic<bool> mStopRequested{false};
    std::atomic<bool> mWaveformEnabled{true};
    std::atomic<float> mGainLinear{1.0f}; // 1.0 == 0dB unity; see setGainDb()

    std::shared_ptr<oboe::AudioStream> mMonitorStream;
    std::unique_ptr<RingBuffer> mMonitorRingBuffer;
    std::mutex mMonitorRingBufferMutex; // guards mMonitorRingBuffer only; never held across close()
    std::atomic<bool> mMonitoringEnabled{false};
    oboe::AudioFormat mMonitorOboeFormat = oboe::AudioFormat::I16;

    std::unique_ptr<RingBuffer> mStreamRingBuffer;
    std::mutex mStreamRingBufferMutex; // guards mStreamRingBuffer only; never held across close()
    std::atomic<bool> mStreamingEnabled{false};

    AudioFormatInfo mFormat;
    oboe::AudioFormat mOboeFormat = oboe::AudioFormat::I32;
    int32_t mChannelCount = 2;

    uint64_t mAaudioFramesSinceLog = 0;
    uint64_t mAaudioBytesSinceLog = 0;
    uint64_t mAaudioNonZeroBytesSinceLog = 0;
    float mAaudioLeftPeakSinceLog = -60.0f;
    float mAaudioRightPeakSinceLog = -60.0f;

    std::atomic<int32_t> mXRunCount{0};
    std::atomic<int64_t> mElapsedMillis{0};

    // Meter state, updated every realtime callback, read by the UI's polling loop.
    std::atomic<float> mLeftPeakDb{-60.0f};
    std::atomic<float> mLeftRmsDb{-60.0f};
    std::atomic<float> mRightPeakDb{-60.0f};
    std::atomic<float> mRightRmsDb{-60.0f};
    std::atomic<bool> mClipping{false};

};

} // namespace djmrec
