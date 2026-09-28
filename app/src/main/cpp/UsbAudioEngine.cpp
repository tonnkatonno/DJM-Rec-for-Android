#include "UsbAudioEngine.h"

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <sstream>

#include "MeterCalculator.h"
#include "writers/WavWriter.h"
#include "writers/FlacWriter.h"

#define TAG "UsbAudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

UsbAudioEngine& UsbAudioEngine::instance() {
    static UsbAudioEngine engine;
    return engine;
}

size_t UsbAudioEngine::bytesPerFrameFor(oboe::AudioFormat format, int32_t channelCount) {
    int bytesPerSample;
    switch (format) {
        case oboe::AudioFormat::I16: bytesPerSample = 2; break;
        case oboe::AudioFormat::I24: bytesPerSample = 3; break;
        case oboe::AudioFormat::Float:
        case oboe::AudioFormat::I32:
        default: bytesPerSample = 4; break;
    }
    return static_cast<size_t>(bytesPerSample) * channelCount;
}

int UsbAudioEngine::open(int32_t audioManagerDeviceId, int32_t sampleRateHint, int32_t channelCount,
                          int32_t bitDepthHint) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStreamOpen.load()) {
        LOGW("open() called while a stream is already open; closing the previous one first");
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    mSourceMode = SourceMode::Oboe;

    mChannelCount = channelCount;
    switch (bitDepthHint) {
        case 16: mOboeFormat = oboe::AudioFormat::I16; break;
        case 24: mOboeFormat = oboe::AudioFormat::I24; break;
        default: mOboeFormat = oboe::AudioFormat::I32; break;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setAudioApi(oboe::AudioApi::AAudio) // only AAudio exposes exclusive MMAP + device binding
        ->setDeviceId(audioManagerDeviceId)
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive) // bypasses AudioFlinger's mixer entirely
        ->setSampleRate(sampleRateHint)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None) // never silently resample
        ->setChannelCount(channelCount)
        ->setChannelConversionAllowed(false)
        ->setFormat(mOboeFormat)
        ->setFormatConversionAllowed(false) // never silently bit-crush/expand
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);

    if (result != oboe::Result::OK && mOboeFormat != oboe::AudioFormat::I32) {
        // Some AAudio HAL implementations only expose exclusive-mode UAC2 endpoints as I32
        // even when the wire format is 24-bit (the 4th byte is just the subslot padding
        // reported in the descriptor) — retry once before giving up.
        LOGW("Exclusive open failed for format %d (%s); retrying with I32",
             static_cast<int>(mOboeFormat), oboe::convertToText(result));
        mOboeFormat = oboe::AudioFormat::I32;
        builder.setFormat(mOboeFormat);
        result = builder.openStream(stream);
    }

    if (result != oboe::Result::OK) {
        LOGW("Exclusive open failed (%s); retrying shared mode with channel conversion allowed",
             oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared)
            ->setInputPreset(oboe::InputPreset::Generic)
            ->setChannelConversionAllowed(true)
            ->setFormatConversionAllowed(true)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        result = builder.openStream(stream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open exclusive low-latency AAudio input stream: %s", oboe::convertToText(result));
        return -1;
    }

    mStream = stream;
    mFormat.sampleRate = mStream->getSampleRate();
    mFormat.channelCount = mStream->getChannelCount();
    mChannelCount = mFormat.channelCount;
    mOboeFormat = mStream->getFormat();
    // We keep the hardware-reported bit depth (from the USB descriptor) for file headers even
    // though the wire format might be padded into I32 — this is the *true* fidelity of the source.
    mFormat.bitsPerSample = bitDepthHint;

    mAaudioFramesSinceLog = 0;
    mAaudioBytesSinceLog = 0;
    mAaudioNonZeroBytesSinceLog = 0;
    mAaudioLeftPeakSinceLog = -60.0f;
    mAaudioRightPeakSinceLog = -60.0f;

    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, mFormat.channelCount);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2; // 2s of headroom
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        mRingBuffer.reset();
        return -1;
    }

    mStreamOpen.store(true, std::memory_order_release);
    startMonitorStreamIfEnabled();
    startStreamingRingBufferIfEnabled();
    LOGI("AAudio input open: %d Hz, %d ch, actual format=%d, sharing=%s, perf=%s",
         mFormat.sampleRate, mFormat.channelCount, static_cast<int>(mOboeFormat),
         oboe::convertToText(mStream->getSharingMode()), oboe::convertToText(mStream->getPerformanceMode()));

    return mFormat.sampleRate;
}

int UsbAudioEngine::openUsbIso(const UsbIsoAudioSource::Config& isoConfig, int32_t sampleRateHint) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStreamOpen.load()) {
        LOGW("openUsbIso() called while a stream is already open; closing the previous one first");
    }
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    mSourceMode = SourceMode::UsbIso;

    // The extracted output is always exactly one stereo pair, regardless of how many channels
    // are actually present on the wire (isoConfig.totalChannels) -- that wire channel count is
    // only used internally by UsbIsoAudioSource for its demux math.
    mUsbIsoSource = std::make_unique<UsbIsoAudioSource>();
    const std::string error = mUsbIsoSource->start(
        isoConfig, [this](const int32_t* frames, size_t count) { onUsbIsoFrames(frames, count); });

    if (!error.empty()) {
        LOGE("Failed to start USB iso capture: %s", error.c_str());
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    // UAC2 clock queries are optional and the DJM-A9 rejects GET_RANGE. Measure the active
    // endpoint cadence before creating an output file so its header matches the real stream.
    const int measuredSampleRate = mUsbIsoSource->waitForMeasuredSampleRate(/*timeoutMs=*/750);
    if (measuredSampleRate <= 0) {
        LOGE("USB iso capture produced no usable sample-rate measurement");
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    mChannelCount = 2;
    mOboeFormat = oboe::AudioFormat::I32;
    mFormat.sampleRate = measuredSampleRate;
    mFormat.channelCount = 2;
    mFormat.bitsPerSample = isoConfig.bitResolution;
    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, 2);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);

    mStreamOpen.store(true, std::memory_order_release);
    startMonitorStreamIfEnabled();
    startStreamingRingBufferIfEnabled();
    LOGI("USB iso capture open: %d Hz, 2ch extracted from a %dch wire "
         "format, format=I32 canonical",
            mFormat.sampleRate, isoConfig.totalChannels);

        return mFormat.sampleRate;
}

int UsbAudioEngine::openRootAlsa(const AlsaPcmAudioSource::Config& alsaConfig) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStreamOpen.load()) {
        LOGW("openRootAlsa() called while a stream is already open; closing the previous one first");
    }
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    mSourceMode = SourceMode::RootAlsa;

    mChannelCount = 2;
    mOboeFormat = oboe::AudioFormat::I32;
    mAlsaSource = std::make_unique<AlsaPcmAudioSource>();
    const std::string error = mAlsaSource->start(
        alsaConfig, [this](const int32_t* frames, size_t count) { onUsbIsoFrames(frames, count); });

    if (!error.empty()) {
        LOGE("Failed to start root ALSA capture: %s", error.c_str());
        mAlsaSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    mFormat.sampleRate = mAlsaSource->openedSampleRate();
    mFormat.channelCount = 2;
    mFormat.bitsPerSample = mAlsaSource->openedBitDepth();
    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, 2);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);
    mStreamOpen.store(true, std::memory_order_release);
    startMonitorStreamIfEnabled();
    startStreamingRingBufferIfEnabled();

    LOGI("Root ALSA capture open: hw:%d,%d @ %dHz, %dbit, native channels=%d, output stereo I32",
         alsaConfig.card, alsaConfig.device, mFormat.sampleRate, mFormat.bitsPerSample,
         mAlsaSource->openedChannels());
    return mFormat.sampleRate;
}

void UsbAudioEngine::onUsbIsoFrames(const int32_t* sourceInterleavedStereo, size_t frameCount) {
    // --- Invoked on UsbIsoAudioSource's libusb event thread: no blocking I/O below. ---
    // Mirrors the tail of onAudioReady() below -- meter update + optional ring-buffer write --
    // but always against a canonical, already-2-channel buffer (no per-format decode needed
    // here; UsbIsoAudioSource already produced left-justified, sign-extended int32 samples).
    //
    // sourceInterleavedStereo points into the source's own reused scratch buffer, so gain is
    // applied into a local thread_local copy rather than mutating it in place.
    static thread_local std::vector<int32_t> gained;
    const size_t sampleCount = frameCount * 2;
    if (gained.size() < sampleCount) gained.resize(sampleCount);
    std::memcpy(gained.data(), sourceInterleavedStereo, sampleCount * sizeof(int32_t));
    applyGain(gained.data(), sampleCount);
    const int32_t* interleavedStereo = gained.data();

    const StereoMeterReading reading =
        MeterCalculator::analyze(interleavedStereo, static_cast<int32_t>(frameCount), oboe::AudioFormat::I32);
    mLeftPeakDb.store(reading.leftPeakDb, std::memory_order_relaxed);
    mLeftRmsDb.store(reading.leftRmsDb, std::memory_order_relaxed);
    mRightPeakDb.store(reading.rightPeakDb, std::memory_order_relaxed);
    mRightRmsDb.store(reading.rightRmsDb, std::memory_order_relaxed);
    mClipping.store(reading.clipping, std::memory_order_relaxed);

    if (mWaveformEnabled.load(std::memory_order_relaxed) && mWaveformAnalyzer) {
        mWaveformAnalyzer->pushFrames(interleavedStereo, frameCount);
    }

    feedMonitor(interleavedStereo, frameCount);
    feedStream(interleavedStereo, frameCount);

    if (mRecording.load(std::memory_order_relaxed) &&
        !mPaused.load(std::memory_order_relaxed) &&
        mRingBuffer) {
        const size_t bytesToWrite = frameCount * 2 * sizeof(int32_t);
        const size_t written =
            mRingBuffer->write(reinterpret_cast<const uint8_t*>(interleavedStereo), bytesToWrite);
        if (written < bytesToWrite) {
            mXRunCount.fetch_add(1, std::memory_order_relaxed);
        }
    }
}

oboe::DataCallbackResult UsbAudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                       int32_t numFrames) {
    // mMonitorStream and the AAudio *input* stream (mStream) share this callback -- Oboe has no
    // per-stream callback object here, so disambiguate by pointer identity.
    if (stream == mMonitorStream.get()) {
        return onMonitorAudioReady(audioData, numFrames);
    }

    // --- REALTIME THREAD: no allocation after warmup, no locks, no blocking I/O below. ---
    static thread_local std::vector<int32_t> canonical;
    const size_t sampleCount = static_cast<size_t>(numFrames) * mChannelCount;
    if (canonical.size() < sampleCount) canonical.resize(sampleCount);

    const size_t inputBytes = bytesPerFrameFor(mOboeFormat, mChannelCount) * static_cast<size_t>(numFrames);
    const auto* inputBytesPtr = static_cast<const uint8_t*>(audioData);
    for (size_t i = 0; i < inputBytes; ++i) {
        if (inputBytesPtr[i] != 0) {
            ++mAaudioNonZeroBytesSinceLog;
        }
    }
    mAaudioBytesSinceLog += inputBytes;

    switch (mOboeFormat) {
        case oboe::AudioFormat::I16: {
            const auto* src = static_cast<const int16_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                canonical[i] = static_cast<int32_t>(src[i]) << 16;
            }
            break;
        }
        case oboe::AudioFormat::I24: {
            // Packed 3-byte little-endian PCM: sign-extend to 32 bits, then left-justify.
            const auto* src = static_cast<const uint8_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                const size_t o = i * 3;
                int32_t v = src[o] | (src[o + 1] << 8) | (src[o + 2] << 16);
                if (v & 0x00800000) v |= static_cast<int32_t>(0xFF000000);
                canonical[i] = v << 8;
            }
            break;
        }
        case oboe::AudioFormat::Float: {
            const auto* src = static_cast<const float*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                const float clamped = std::max(-1.0f, std::min(1.0f, src[i]));
                canonical[i] = static_cast<int32_t>(clamped * 2147483647.0f);
            }
            break;
        }
        case oboe::AudioFormat::I32:
        default:
            std::memcpy(canonical.data(), audioData, sampleCount * sizeof(int32_t));
            break;
    }

    applyGain(canonical.data(), sampleCount);

    // Live stereo metering — always computed, even while paused/stopped, so the UI VU meter
    // reflects the signal actually present at the mixer's output at all times.
    const StereoMeterReading reading =
        MeterCalculator::analyze(canonical.data(), numFrames, oboe::AudioFormat::I32);
    mLeftPeakDb.store(reading.leftPeakDb, std::memory_order_relaxed);
    mLeftRmsDb.store(reading.leftRmsDb, std::memory_order_relaxed);
    mRightPeakDb.store(reading.rightPeakDb, std::memory_order_relaxed);
    mRightRmsDb.store(reading.rightRmsDb, std::memory_order_relaxed);
    mClipping.store(reading.clipping, std::memory_order_relaxed);

    // The monitor ring buffer/output stream hard-assume interleaved stereo (matching the other
    // two capture paths, which are always exactly 2ch by construction); this AAudio path is the
    // only one that could in principle negotiate a different channel count.
    if (mChannelCount == 2) {
        feedMonitor(canonical.data(), static_cast<size_t>(numFrames));
        feedStream(canonical.data(), static_cast<size_t>(numFrames));
    }

    mAaudioLeftPeakSinceLog = std::max(mAaudioLeftPeakSinceLog, reading.leftPeakDb);
    mAaudioRightPeakSinceLog = std::max(mAaudioRightPeakSinceLog, reading.rightPeakDb);
    mAaudioFramesSinceLog += static_cast<uint64_t>(numFrames);
    if (mAaudioFramesSinceLog >= static_cast<uint64_t>(std::max(1, mFormat.sampleRate))) {
        LOGI("AAudio payload nonzero bytes=%llu/%llu; decoded peaks L=%.1f dBFS R=%.1f dBFS; "
             "format=%d ch=%d rate=%d",
             static_cast<unsigned long long>(mAaudioNonZeroBytesSinceLog),
             static_cast<unsigned long long>(mAaudioBytesSinceLog),
             mAaudioLeftPeakSinceLog, mAaudioRightPeakSinceLog,
             static_cast<int>(mOboeFormat), mChannelCount, mFormat.sampleRate);
        mAaudioFramesSinceLog = 0;
        mAaudioBytesSinceLog = 0;
        mAaudioNonZeroBytesSinceLog = 0;
        mAaudioLeftPeakSinceLog = -60.0f;
        mAaudioRightPeakSinceLog = -60.0f;
    }

    if (mWaveformEnabled.load(std::memory_order_relaxed) && mWaveformAnalyzer) {
        mWaveformAnalyzer->pushFrames(canonical.data(), numFrames);
    }

    if (mRecording.load(std::memory_order_relaxed) &&
        !mPaused.load(std::memory_order_relaxed) &&
        mRingBuffer) {
        const size_t bytesToWrite = sampleCount * sizeof(int32_t);
        const size_t written =
            mRingBuffer->write(reinterpret_cast<const uint8_t*>(canonical.data()), bytesToWrite);
        if (written < bytesToWrite) {
            // Encoder thread fell behind (e.g. slow storage): count it, never block the
            // audio thread to catch up.
            mXRunCount.fetch_add(1, std::memory_order_relaxed);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void UsbAudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    // mMonitorStream and mStream share this callback; a monitor-only failure (e.g. a wired
    // headset unplugged mid-session) must NOT be treated as the capture/recording stream dying.
    if (stream == mMonitorStream.get()) {
        LOGW("Monitor output stream closed unexpectedly: %s", oboe::convertToText(error));
        // Oboe has already fully closed this stream by the time this fires (its contract), so
        // no further callbacks can be in flight on it -- safe to drop the reference and reset
        // the ring buffer without calling close() again.
        mMonitorStream.reset();
        mMonitoringEnabled.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> bufLock(mMonitorRingBufferMutex);
        mMonitorRingBuffer.reset();
        return;
    }

    // Typically fired when the USB mixer is unplugged mid-session. We can't safely reopen
    // from this callback thread; flag state so the Kotlin layer can react (stop cleanly,
    // show "device disconnected") on its next status check.
    LOGE("Stream closed unexpectedly: %s", oboe::convertToText(error));
    mStreamOpen.store(false, std::memory_order_release);
}

bool UsbAudioEngine::startRecording(const std::string& path, ContainerFormat format) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mStreamOpen.load() || mRecording.load()) return false;

    switch (format) {
        case ContainerFormat::Wav: mWriter = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: mWriter = std::make_unique<FlacWriter>(); break;
    }

    if (!mWriter->open(path, mFormat)) {
        LOGE("Writer failed to open output file: %s", path.c_str());
        mWriter.reset();
        return false;
    }

    mXRunCount.store(0, std::memory_order_relaxed);
    mElapsedMillis.store(0, std::memory_order_relaxed);
    mStopRequested.store(false, std::memory_order_relaxed);
    mPaused.store(false, std::memory_order_relaxed);
    mRingBuffer->reset();
    if (mWaveformAnalyzer) mWaveformAnalyzer->reset();
    mRecording.store(true, std::memory_order_release);

    mEncoderThread = std::thread(&UsbAudioEngine::encoderThreadLoop, this);
    return true;
}

void UsbAudioEngine::pauseRecording() {
    mPaused.store(true, std::memory_order_release);
}

void UsbAudioEngine::resumeRecording() {
    mPaused.store(false, std::memory_order_release);
}

int64_t UsbAudioEngine::stopRecording() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mRecording.load()) return 0;

    mStopRequested.store(true, std::memory_order_release);
    if (mEncoderThread.joinable()) mEncoderThread.join();

    const int64_t duration = mElapsedMillis.load(std::memory_order_relaxed);
    if (mWriter) {
        if (!mWriter->close()) LOGE("Writer failed while finalizing output file");
        mWriter.reset();
    }
    mRecording.store(false, std::memory_order_release);
    return duration;
}

void UsbAudioEngine::closeEngine() {
    if (mRecording.load()) {
        stopRecording();
    }

    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    // Monitoring's on/off *preference* (mMonitoringEnabled) deliberately survives this, same as
    // mWaveformEnabled -- only the stream itself is torn down, so it comes back automatically
    // on the next open*() call via startMonitorStreamIfEnabled().
    stopMonitorStream();
    {
        std::lock_guard<std::mutex> bufLock(mStreamRingBufferMutex);
        mStreamRingBuffer.reset(); // preference (mStreamingEnabled) survives, same as monitoring/waveform
    }
    mRingBuffer.reset();
    mSourceMode = SourceMode::None;
    mStreamOpen.store(false, std::memory_order_release);
}

void UsbAudioEngine::encoderThreadLoop() {
    constexpr size_t kChunkFrames = 960; // ~20ms chunks @48kHz; small enough for low file-write latency
    std::vector<int32_t> chunk(kChunkFrames * mFormat.channelCount);
    uint64_t framesEncoded = 0;
    const size_t bytesPerFrame = sizeof(int32_t) * mFormat.channelCount;

    while (true) {
        if (mStopRequested.load(std::memory_order_acquire) && mRingBuffer->availableToRead() == 0) {
            break;
        }
        if (mPaused.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        const size_t bytesAvailable = mRingBuffer->availableToRead();
        if (bytesAvailable < bytesPerFrame) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        const size_t framesToRead = std::min(kChunkFrames, bytesAvailable / bytesPerFrame);
        const size_t bytesToRead = framesToRead * bytesPerFrame;
        const size_t bytesRead =
            mRingBuffer->read(reinterpret_cast<uint8_t*>(chunk.data()), bytesToRead);
        const size_t framesRead = bytesRead / bytesPerFrame;

        if (framesRead > 0 && mWriter) {
            if (!mWriter->writeFrames(chunk.data(), framesRead)) {
                LOGE("Encoder write failed after %llu frames",
                     static_cast<unsigned long long>(framesEncoded));
                break;
            }
            framesEncoded += framesRead;
            mElapsedMillis.store(
                static_cast<int64_t>(framesEncoded * 1000 / mFormat.sampleRate),
                std::memory_order_relaxed);
        }
    }
}

void UsbAudioEngine::getLevels(float outLevels[4]) const {
    outLevels[0] = mLeftPeakDb.load(std::memory_order_relaxed);
    outLevels[1] = mLeftRmsDb.load(std::memory_order_relaxed);
    outLevels[2] = mRightPeakDb.load(std::memory_order_relaxed);
    outLevels[3] = mRightRmsDb.load(std::memory_order_relaxed);
}

bool UsbAudioEngine::isClipping() const {
    return mClipping.load(std::memory_order_relaxed);
}

int64_t UsbAudioEngine::getElapsedMillis() const {
    return mElapsedMillis.load(std::memory_order_relaxed);
}

int32_t UsbAudioEngine::getXRunCount() const {
    return mXRunCount.load(std::memory_order_relaxed);
}

void UsbAudioEngine::getUsbIsoTransferStats(uint64_t outStats[7]) const {
    std::fill(outStats, outStats + 7, 0);
    if (!mUsbIsoSource) {
        return;
    }
    const auto stats = mUsbIsoSource->getTransferStats();
    outStats[0] = stats.packetsCompleted;
    outStats[1] = stats.packetsMissed;
    outStats[2] = stats.packetsEmpty;
    outStats[3] = stats.packetsPartial;
    outStats[4] = stats.bytesReceived;
    outStats[5] = stats.nonZeroBytesReceived;
    outStats[6] = stats.resubmitFailures;
}

std::string UsbAudioEngine::getDiagnosticSummary() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const char* sourceMode = "none";
    switch (mSourceMode) {
        case SourceMode::Oboe: sourceMode = "aaudio"; break;
        case SourceMode::UsbIso: sourceMode = "usb_iso"; break;
        case SourceMode::RootAlsa: sourceMode = "root_alsa"; break;
        case SourceMode::None: break;
    }

    std::ostringstream out;
    out << "source_mode=" << sourceMode << '\n'
        << "stream_open=" << (mStreamOpen.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "recording=" << (mRecording.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "paused=" << (mPaused.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "format=" << mFormat.sampleRate << "Hz/" << mFormat.channelCount
        << "ch/" << mFormat.bitsPerSample << "bit\n"
        << "xrun_count=" << mXRunCount.load(std::memory_order_relaxed) << '\n'
        << "elapsed_ms=" << mElapsedMillis.load(std::memory_order_relaxed) << '\n'
        << "levels_db=peak_l:" << mLeftPeakDb.load(std::memory_order_relaxed)
        << " rms_l:" << mLeftRmsDb.load(std::memory_order_relaxed)
        << " peak_r:" << mRightPeakDb.load(std::memory_order_relaxed)
        << " rms_r:" << mRightRmsDb.load(std::memory_order_relaxed)
        << " clipping:" << (mClipping.load(std::memory_order_relaxed) ? "true" : "false");
    if (mUsbIsoSource) {
        out << "\n--- usb_iso_source ---\n" << mUsbIsoSource->diagnosticSummary();
    }
    return out.str();
}

void UsbAudioEngine::getWaveformBins(float* outBins) const {
    if (mWaveformAnalyzer) {
        mWaveformAnalyzer->getBins(outBins);
    } else {
        std::memset(outBins, 0, kWaveformBinCount * 4 * sizeof(float));
    }
}

void UsbAudioEngine::setWaveformEnabled(bool enabled) {
    mWaveformEnabled.store(enabled, std::memory_order_release);
}

void UsbAudioEngine::setGainDb(float db) {
    // +24dB covers "the mixer barely touches yellow" cases without inviting a gain that makes
    // clipping the default outcome; -24dB is symmetric headroom the other way.
    const float clamped = std::clamp(db, -24.0f, 24.0f);
    const float linear = std::pow(10.0f, clamped / 20.0f);
    mGainLinear.store(linear, std::memory_order_relaxed);
}

void UsbAudioEngine::applyGain(int32_t* samples, size_t sampleCount) const {
    const float gain = mGainLinear.load(std::memory_order_relaxed);
    if (gain == 1.0f) return; // unity: skip the pass entirely
    constexpr float kMax = 2147483647.0f;
    constexpr float kMin = -2147483648.0f;
    for (size_t i = 0; i < sampleCount; ++i) {
        const float scaled = static_cast<float>(samples[i]) * gain;
        samples[i] = static_cast<int32_t>(std::clamp(scaled, kMin, kMax));
    }
}

void UsbAudioEngine::setMonitoringEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (enabled == mMonitoringEnabled.load(std::memory_order_relaxed)) return;
    if (!enabled) {
        mMonitoringEnabled.store(false, std::memory_order_release);
        stopMonitorStream();
        return;
    }
    mMonitoringEnabled.store(true, std::memory_order_release);
    startMonitorStreamIfEnabled(); // no-op if no capture stream is open yet; retried on next open*()
}

void UsbAudioEngine::feedMonitor(const int32_t* interleavedStereo, size_t frameCount) {
    if (!mMonitoringEnabled.load(std::memory_order_relaxed)) return;
    std::unique_lock<std::mutex> lock(mMonitorRingBufferMutex, std::try_to_lock);
    if (!lock.owns_lock() || !mMonitorRingBuffer) return; // toggling right now, or not open yet
    const size_t bytes = frameCount * 2 * sizeof(int32_t);
    mMonitorRingBuffer->write(reinterpret_cast<const uint8_t*>(interleavedStereo), bytes);
    // No xrun counting here -- a dropped monitor frame is a minor, self-correcting UX detail
    // (the next callback just catches up), unlike the recording ring buffer where a drop is
    // permanently lost audio.
}

void UsbAudioEngine::setStreamingEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (enabled == mStreamingEnabled.load(std::memory_order_relaxed)) return;
    if (!enabled) {
        mStreamingEnabled.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> bufLock(mStreamRingBufferMutex);
        mStreamRingBuffer.reset();
        return;
    }
    mStreamingEnabled.store(true, std::memory_order_release);
    startStreamingRingBufferIfEnabled(); // no-op if no capture stream is open yet; retried on next open*()
}

void UsbAudioEngine::startStreamingRingBufferIfEnabled() {
    // Caller must already hold mControlMutex.
    if (!mStreamingEnabled.load(std::memory_order_relaxed)) return;
    if (mFormat.sampleRate <= 0) return; // no capture format finalized yet; retried on open*()
    std::lock_guard<std::mutex> bufLock(mStreamRingBufferMutex);
    if (mStreamRingBuffer) return; // already allocated -- idempotent
    // 1s of headroom: the consumer here is a Kotlin coroutine polling on its own schedule
    // (not a hardware callback like the monitor output stream), so it tolerates more jitter.
    const size_t frames = static_cast<size_t>(mFormat.sampleRate) * 1;
    mStreamRingBuffer = std::make_unique<RingBuffer>(frames * 2 * sizeof(int16_t));
}

void UsbAudioEngine::feedStream(const int32_t* interleavedStereo, size_t frameCount) {
    if (!mStreamingEnabled.load(std::memory_order_relaxed)) return;
    std::unique_lock<std::mutex> lock(mStreamRingBufferMutex, std::try_to_lock);
    if (!lock.owns_lock() || !mStreamRingBuffer) return; // toggling right now, or not open yet
    const size_t sampleCount = frameCount * 2;
    static thread_local std::vector<int16_t> pcm16;
    if (pcm16.size() < sampleCount) pcm16.resize(sampleCount);
    for (size_t i = 0; i < sampleCount; ++i) {
        pcm16[i] = static_cast<int16_t>(interleavedStereo[i] >> 16);
    }
    mStreamRingBuffer->write(reinterpret_cast<const uint8_t*>(pcm16.data()), sampleCount * sizeof(int16_t));
    // No xrun counting here -- same rationale as feedMonitor: a dropped streaming frame just
    // means a brief encoder underrun, not permanently lost recorded audio.
}

size_t UsbAudioEngine::pullStreamPcm16(int16_t* outInterleavedS16Stereo, size_t maxFrames) {
    std::unique_lock<std::mutex> lock(mStreamRingBufferMutex, std::try_to_lock);
    if (!lock.owns_lock() || !mStreamRingBuffer) return 0;
    const size_t maxBytes = maxFrames * 2 * sizeof(int16_t);
    const size_t bytesRead =
        mStreamRingBuffer->read(reinterpret_cast<uint8_t*>(outInterleavedS16Stereo), maxBytes);
    return bytesRead / (2 * sizeof(int16_t));
}

void UsbAudioEngine::startMonitorStreamIfEnabled() {
    // Caller must already hold mControlMutex.
    if (!mMonitoringEnabled.load(std::memory_order_relaxed)) return;
    if (mMonitorStream) return; // already running -- idempotent
    if (mFormat.sampleRate <= 0) return; // no capture format finalized yet; retried on open*()

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared) // let AudioFlinger route to wired/BT headset normally
        ->setSampleRate(mFormat.sampleRate)
        ->setChannelCount(2)
        ->setFormat(oboe::AudioFormat::I16)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result openResult = builder.openStream(stream);
    if (openResult != oboe::Result::OK) {
        LOGE("Monitor output stream open failed: %s", oboe::convertToText(openResult));
        return;
    }

    mMonitorOboeFormat = stream->getFormat();
    {
        std::lock_guard<std::mutex> bufLock(mMonitorRingBufferMutex);
        // ~400ms: generous headroom against scheduling jitter between the capture producer
        // thread and this stream's own callback thread, without meaningfully adding to the
        // monitoring latency for what this feature is for (hearing crackle/gain issues, not
        // beat-matching by ear against the monitor output).
        const size_t frames = static_cast<size_t>(mFormat.sampleRate) * 400 / 1000;
        mMonitorRingBuffer = std::make_unique<RingBuffer>(frames * 2 * sizeof(int32_t));
    }

    const oboe::Result startResult = stream->requestStart();
    if (startResult != oboe::Result::OK) {
        LOGE("Monitor output stream start failed: %s", oboe::convertToText(startResult));
        stream->close();
        std::lock_guard<std::mutex> bufLock(mMonitorRingBufferMutex);
        mMonitorRingBuffer.reset();
        return;
    }
    mMonitorStream = stream;
    LOGI("Live monitoring started: %d Hz, format=%d, sharing=%s",
         mFormat.sampleRate, static_cast<int>(mMonitorOboeFormat),
         oboe::convertToText(stream->getSharingMode()));
}

void UsbAudioEngine::stopMonitorStream() {
    // Caller must already hold mControlMutex.
    if (mMonitorStream) {
        mMonitorStream->requestStop();
        mMonitorStream->close(); // blocks until this stream's own callback thread fully stops --
                                  // safe to reset the ring buffer right after, no thread can still
                                  // be reading it.
        mMonitorStream.reset();
    }
    std::lock_guard<std::mutex> bufLock(mMonitorRingBufferMutex);
    mMonitorRingBuffer.reset();
}

oboe::DataCallbackResult UsbAudioEngine::onMonitorAudioReady(void* audioData, int32_t numFrames) {
    // --- REALTIME THREAD (this stream's own callback thread): no blocking I/O below. ---
    constexpr int32_t kChannels = 2;
    const size_t sampleCount = static_cast<size_t>(numFrames) * kChannels;
    static thread_local std::vector<int32_t> canonical;
    if (canonical.size() < sampleCount) canonical.resize(sampleCount);

    size_t gotSamples = 0;
    {
        std::unique_lock<std::mutex> lock(mMonitorRingBufferMutex, std::try_to_lock);
        if (lock.owns_lock() && mMonitorRingBuffer) {
            const size_t gotBytes = mMonitorRingBuffer->read(
                reinterpret_cast<uint8_t*>(canonical.data()), sampleCount * sizeof(int32_t));
            gotSamples = gotBytes / sizeof(int32_t);
        }
    }
    // Underrun (buffer starved, or mid-toggle): pad with silence rather than stale/garbage data.
    if (gotSamples < sampleCount) {
        std::fill(canonical.begin() + static_cast<long>(gotSamples), canonical.begin() + static_cast<long>(sampleCount), 0);
    }

    switch (mMonitorOboeFormat) {
        case oboe::AudioFormat::I16: {
            auto* dst = static_cast<int16_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) dst[i] = static_cast<int16_t>(canonical[i] >> 16);
            break;
        }
        case oboe::AudioFormat::I24: {
            auto* dst = static_cast<uint8_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                const int32_t v = canonical[i] >> 8;
                dst[i * 3] = static_cast<uint8_t>(v & 0xFF);
                dst[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
                dst[i * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
            }
            break;
        }
        case oboe::AudioFormat::Float: {
            auto* dst = static_cast<float*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                dst[i] = static_cast<float>(canonical[i]) / 2147483648.0f;
            }
            break;
        }
        case oboe::AudioFormat::I32:
        default:
            std::memcpy(audioData, canonical.data(), sampleCount * sizeof(int32_t));
            break;
    }
    return oboe::DataCallbackResult::Continue;
}

} // namespace djmrec
