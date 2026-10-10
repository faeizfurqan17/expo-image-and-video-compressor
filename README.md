<p align="center">
  <h1 align="center">expo-image-and-video-compressor</h1>
</p>

<p align="center">
  <b>The only Expo-native video & image compressor with full hardware acceleration.</b><br/>
  H.264 & HEVC encoding powered by MediaCodec (Android) and VideoToolbox (iOS).<br/>
  No FFmpeg. No config plugins. No ejecting. Just install and compress.
</p>

<p align="center">
  <a href="https://www.npmjs.com/package/expo-image-and-video-compressor"><img src="https://img.shields.io/npm/v/expo-image-and-video-compressor.svg?style=flat-square" alt="npm version" /></a>
  <a href="https://www.npmjs.com/package/expo-image-and-video-compressor"><img src="https://img.shields.io/npm/dm/expo-image-and-video-compressor.svg?style=flat-square" alt="npm downloads" /></a>
  <a href="https://github.com/faeizfurqan17/expo-image-and-video-compressor"><img src="https://img.shields.io/github/stars/faeizfurqan17/expo-image-and-video-compressor?style=flat-square" alt="GitHub stars" /></a>
  <a href="https://github.com/faeizfurqan17/expo-image-and-video-compressor/blob/main/LICENSE"><img src="https://img.shields.io/npm/l/expo-image-and-video-compressor.svg?style=flat-square" alt="license" /></a>
  <img src="https://img.shields.io/badge/platforms-iOS%20%7C%20Android-brightgreen?style=flat-square" alt="platforms" />
  <img src="https://img.shields.io/badge/Expo%20SDK-51%2B-blue?style=flat-square" alt="expo sdk" />
</p>

---

## Table of Contents

- [Why This Package?](#why-this-package)
- [Upgrading to v1.0.1](#upgrading-to-v101)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
  - [compressVideo()](#compressfileurl-options-onprogress)
  - [compressImage()](#compressimagefileurl-options)
  - [cancel()](#canceluuid)
  - [getMetadata()](#getmetadatafileurl)
  - [activateBackgroundTask()](#activatebackgroundtaskonexpired)
  - [deactivateBackgroundTask()](#deactivatebackgroundtask)
- [Advanced Usage](#advanced-usage)
- [Features](#features)
- [Platform Support](#platform-support)
- [Contributing](#contributing)
- [License](#license)

---

## Why This Package?

Most React Native compression libraries either depend on FFmpeg (adding ~9 MB to your APK), require config plugins and ejecting, or only handle images. This package is built as a **true Expo Module** — it plugs directly into the Expo module system with zero native setup.

| Feature | This package | react-native-compressor | expo-image-manipulator |
|---------|:---:|:---:|:---:|
| True Expo Module (no config plugin) | **Yes** | No | Yes |
| Video compression | **H.264 + HEVC** | H.264 only | No |
| Image compression | **Yes** | Yes | Resize only |
| Full hardware acceleration | **Yes** | Partial | No |
| HEVC / H.265 support | **Yes** | No | No |
| Zero config in managed workflow | **Yes** | No | Yes |
| Speed presets (ultrafast / fast / balanced) | **Yes** | No | No |
| Real-time progress callbacks | **Yes** | Yes | No |
| Mid-flight cancellation | **Yes** | Yes | No |
| iOS background task support | **Yes** | Yes | No |
| EXIF metadata preservation | **Yes** | Yes | No |
| Audio pass-through (no re-encode) | **Yes** | Yes | No |
| APK size impact | **~50 KB** | ~50 KB | Part of Expo |

---

## Upgrading to v1.0.1

If you're on an older version (0.2.x or 0.3.x), upgrade to receive automatic compatible updates going forward:

```bash
npm install expo-image-and-video-compressor@latest
```

> **Why upgrade?** Versions below 1.0.1 use `0.x` semver, which means `npm install` won't auto-update across minor versions (e.g. `^0.2.0` won't resolve to `0.3.x`). Starting from 1.0.1, `^1.0.1` will correctly receive all future compatible updates. The API is fully stable — no breaking changes from 0.3.1.

---

## Installation

```bash
npx expo install expo-image-and-video-compressor
```

Or with npm / yarn:

```bash
npm install expo-image-and-video-compressor
# or
yarn add expo-image-and-video-compressor
```

**Requirements:**
- Expo SDK 51 or later
- `expo-modules-core` (included with Expo SDK)
- Works in both **managed** and **bare** workflows — no ejecting required

---

## Quick Start

### Compress a Video

```ts
import { compressVideo } from 'expo-image-and-video-compressor';

const result = await compressVideo(videoUri, {
  videoEncodingMode: 'vbr',        // use Variable Bitrate Encoding
  videoBitrateTarget: 2_500_000,   // 2.5 Mbps
  videoMaxDimensionLongEdge: 1080, // Scale down to 1080p
  videoCodec: 'h264',              // or 'hevc' for ~40% smaller output
  speed: 'ultrafast',              // Fastest encoding
}, (progress) => {
  console.log(`Compressing: ${Math.round(progress * 100)}%`);
});

console.log('Compressed file:', result);
```

### Compress an Image

```ts
import { compressImage } from 'expo-image-and-video-compressor';

const result = await compressImage(imageUri, {
  maxWidth: 1080,
  maxHeight: 1920,
  quality: 0.8,          // JPEG quality (0.0 - 1.0)
  output: 'jpg',         // or 'png'
});

console.log('Compressed image:', result);
```

### Cancel an In-Progress Compression

```ts
import { compressVideo, cancel } from 'expo-image-and-video-compressor';

let cancellationId: string;

const promise = compressVideo(videoUri, {
  getCancellationId: (id) => {
    cancellationId = id;
  },
});

// User taps "Cancel"
cancel(cancellationId);
```

### Get Video Metadata

```ts
import { getMetadata } from 'expo-image-and-video-compressor';

const meta = await getMetadata(videoUri);
console.log(meta);
// { width: 1920, height: 1080, duration: 30.5, size: 89400000, bitrate: 23000000, extension: 'mp4' }
```

### Background Compression (iOS)

```ts
import { compressVideo, activateBackgroundTask, deactivateBackgroundTask } from 'expo-image-and-video-compressor';

// Keep compression alive when app goes to background
await activateBackgroundTask(() => {
  console.log('Background time is about to expire');
});

const result = await compressVideo(largeVideoUri);

deactivateBackgroundTask();
```

---

## API Reference

### `compressVideo(fileUrl, options?, onProgress?)`

Compresses a video file using the device's hardware encoder.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `fileUrl` | `string` | File URI of the source video |
| `options` | `CompressOptions` | Compression configuration (see below) |
| `onProgress` | `(progress: number) => void` | Progress callback, value ranges from `0.0` to `1.0` |

**Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `videoMaxDimensionShortEdge` | `number` |  | Maximum output dimension (width or height) in pixels. Aspect ratio is preserved. (e.g. given a input resolution `2420 × 1668` with `videoMaxDimensionShortEdge: 1080`, the output resolution will be `1566 × 1080`) |
| `videoMaxDimensionLongEdge` | `number` |  | Maximum output dimension (width or height) in pixels. Aspect ratio is preserved. (e.g. given a input resolution `2420 × 1668` with `videoMaxDimensionLongEdge: 1080`, the output resolution will be `1080 × 744`) |
| `videoCodec` | `'h264' \| 'hevc'` | `'h264'` | Output video videoCodec. HEVC produces ~40% smaller files but requires iOS 11+ / Android 5+. |
| `speed` | `'ultrafast' \| 'fast' \| 'balanced'` | `'ultrafast'` | Encoding speed preset. Faster = larger file, slower = better compression ratio. |
| `videoEncodingMode` | `'vbr' \| 'cbr' \| 'cq'` | `'vbr'` | Rate control. Android: `BITRATE_MODE_CBR`/`VBR` (CBR falls back to VBR if the encoder lacks it). iOS: no true CBR in AVAssetWriter, so `'cbr'` is approximated with a 1-second data-rate cap equal to `videoBitrateTarget`. |
| `videoBitrateTarget` | `number` | Auto | Target bitrate in bits per second. Auto-calculated from resolution when omitted. |
| `videoBitrateMax` | `number` | none | Peak cap in bps (like ffmpeg `-maxrate`). **VBR and CQ only** (a type error with `videoEncodingMode: 'cbr'`). iOS: enforced via `kVTCompressionPropertyKey_DataRateLimits`. Android: `max-bitrate` format key, only honored by some encoders. |
| `videoQuality` | `number` | `0.5` | 0–1. **CQ only** (like ffmpeg `-crf`; `videoBitrateTarget` is ignored). iOS: `kVTCompressionPropertyKey_Quality`. Android: `BITRATE_MODE_CQ`, falling back to VBR if the encoder lacks it. Output size depends on content. |
| `videoKeyframeInterval` | `number` | Android: `1`, iOS: encoder default | Max keyframe interval in seconds. Shorter = faster seeking, larger files. |
| `audioCodec` | `'aac' \| 'copy'` | iOS `'aac'`; Android `'copy'` (or `'aac'` if `audioBitrate` is set) | `'aac'` re-encodes to AAC-LC; `'copy'` keeps the original audio stream. |
| `audioBitrate` | `number` | `128000` | AAC bitrate in bps. Ignored with `'copy'`. |
| `minimumFileSizeForCompress` | `number` | `0` | Skip compression if the source file is smaller than this value (in bytes). |
| `getCancellationId` | `(id: string) => void` | — | Callback that receives a unique ID. Pass this ID to `cancel()` to abort compression. |
| `progressDivider` | `number` | `0` | Throttle progress events. `0` = report every frame, `5` = report every 5%. |

**Returns:** `Promise<string>` — File URI of the compressed video.

---

### `compressImage(fileUrl, options?)`

Compresses an image file with resize and quality control. Preserves EXIF metadata and handles orientation automatically.

**Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `fileUrl` | `string` | File URI or base64 string of the source image |
| `options` | `ImageCompressOptions` | Compression configuration (see below) |

**Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `maxWidth` | `number` | `1280` | Maximum output width in pixels. Image is scaled proportionally. |
| `maxHeight` | `number` | `1280` | Maximum output height in pixels. Image is scaled proportionally. |
| `quality` | `number` | `0.8` | JPEG compression quality (`0.0` - `1.0`). Ignored when output is `'png'`. |
| `output` | `'jpg' \| 'png'` | `'jpg'` | Output image format. |
| `input` | `'uri' \| 'base64'` | `'uri'` | Input type — pass `'base64'` if `fileUrl` is a base64-encoded string. |

**Returns:** `Promise<string>` — File URI of the compressed image. Returns the original URI if compression does not reduce file size.

---

### `cancel(uuid)`

Cancels an in-progress compression.

| Parameter | Type | Description |
|-----------|------|-------------|
| `uuid` | `string` | The cancellation ID received via the `getCancellationId` callback |

---

### `getMetadata(fileUrl)`

Retrieves metadata for a video file.

| Parameter | Type | Description |
|-----------|------|-------------|
| `fileUrl` | `string` | File URI of the video |

**Returns:** `Promise<VideoMetadata>`

```ts
interface VideoMetadata {
  width: number;       // Video width in pixels
  height: number;      // Video height in pixels
  duration: number;    // Duration in seconds
  size: number;        // File size in bytes
  bitrate?: number;    // Bitrate in bps
  extension: string;   // File extension (e.g. 'mp4')
}
```

---

### `activateBackgroundTask(onExpired?)`

Activates an iOS background task to keep compression running when the app is moved to the background.

| Parameter | Type | Description |
|-----------|------|-------------|
| `onExpired` | `() => void` | Optional callback invoked when background time is about to expire |

**Returns:** `Promise<string>` — Background task identifier.

> **Note:** This is an iOS-only feature. On Android, compression continues in the background by default.

---

### `deactivateBackgroundTask()`

Ends the iOS background task. Call this after compression completes.

**Returns:** `Promise<void>`

---

## Advanced Usage

### HEVC for Smaller Files

HEVC (H.265) produces approximately **40% smaller files** at the same visual quality compared to H.264. Use it when targeting modern devices:

```ts
const result = await compressVideo(videoUri, {
  videoCodec: 'hevc',
  videoMaxDimensionLongEdge: 1080,
  speed: 'fast',
});
```

> **Compatibility:** HEVC encoding requires iOS 11+ and Android 5.0+ (API 21+). All modern devices support it.

### Compress Before Upload

A common pattern for chat apps and social media:

```ts
import { compressVideo, compressImage, getMetadata } from 'expo-image-and-video-compressor';

async function prepareForUpload(uri: string, type: 'video' | 'image') {
  if (type === 'video') {
    const meta = await getMetadata(uri);
    console.log(`Original: ${(meta.size / 1_000_000).toFixed(1)} MB`);

    const compressed = await compressVideo(uri, {
      videoEncodingMode: 'vbr',
      videoBitrateTarget: 2_500_000,
      videoMaxDimensionLongEdge: 720,
      videoCodec: 'hevc',
    }, (p) => updateProgressBar(p));

    const newMeta = await getMetadata(compressed);
    console.log(`Compressed: ${(newMeta.size / 1_000_000).toFixed(1)} MB`);
    return compressed;
  }

  return compressImage(uri, {
    maxWidth: 1080,
    maxHeight: 1080,
    quality: 0.7,
  });
}
```

### Skip Small Files

Avoid unnecessary compression on files that are already small:

```ts
const result = await compressVideo(videoUri, {
  minimumFileSizeForCompress: 5_000_000, // Skip if under 5 MB
});
```

---

## Features

| Feature | Description |
|---------|-------------|
| **H.264 & HEVC encoding** | Hardware-accelerated video encoding via platform APIs |
| **Image compression** | JPEG and PNG compression with resize and quality control |
| **Frame dropping** | Automatically drops frames for high-fps sources to reduce output size |
| **Auto-scaling** | Respects aspect ratio — set `videoMaxDimensionLongEdge` / `videoMaxDimensionShortEdge` and let the encoder handle the rest |
| **Orientation handling** | Correctly preserves portrait and landscape orientation for both images and video |
| **EXIF preservation** | Copies metadata from source to compressed images |
| **Audio pass-through** | Copies the audio track without re-encoding — no quality loss |
| **Progress tracking** | Real-time progress callbacks from `0.0` to `1.0` |
| **Cancellation** | Cancel any compression mid-flight with a single function call |
| **Background support** | iOS background task API keeps compression alive when the app is backgrounded |

---

## Platform Support

| Feature | iOS | Android |
|---------|:---:|:-------:|
| H.264 compression | iOS 8+ | API 16+ |
| HEVC compression | iOS 11+ | API 21+ |
| Image compression | iOS 8+ | API 16+ |
| Progress tracking | Yes | Yes |
| Cancellation | Yes | Yes |
| Background task | Yes | Native support |

---

## Contributing

Contributions, issues, and feature requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

---

## License

[MIT](LICENSE)
