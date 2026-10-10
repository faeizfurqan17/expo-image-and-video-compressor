import React, { useState, useRef, useEffect } from 'react';
import {
  SafeAreaView,
  ScrollView,
  Text,
  View,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  Alert,
  Platform,
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { useVideoPlayer, VideoView } from 'expo-video';
import { compressVideo, compressImage, cancel, getMetadata } from 'expo-image-and-video-compressor';
import type { CompressOptions } from 'expo-image-and-video-compressor';
import { Image } from 'react-native';
import { requireNativeModule } from 'expo';

type CompressionResult = {
  inputSize: string;
  outputSize: string;
  ratio: string;
  duration: string;
  outputUri: string;
};

export default function App() {
  const [selectedVideo, setSelectedVideo] = useState<string | null>(null);
  const [videoMeta, setVideoMeta] = useState<string>('');
  const [compressing, setCompressing] = useState(false);
  const [progress, setProgress] = useState(0);
  const [result, setResult] = useState<CompressionResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selectedCodec, setSelectedCodec] = useState<'h264' | 'hevc'>('h264');
  const [selectedSpeed, setSelectedSpeed] = useState<'ultrafast' | 'fast' | 'balanced'>('ultrafast');
  const [selectedMaxSize, setSelectedMaxSize] = useState(1080);
  const cancellationId = useRef<string | null>(null);
  const [currentUuid, setCurrentUuid] = useState<string | null>(null);
  const [compressedUri, setCompressedUri] = useState<string | null>(null);

  // Image compression state
  const [selectedImage, setSelectedImage] = useState<string | null>(null);
  const [imageCompressing, setImageCompressing] = useState(false);
  const [imageResult, setImageResult] = useState<{ inputSize: string; outputSize: string; ratio: string; outputUri: string } | null>(null);
  const [imageError, setImageError] = useState<string | null>(null);

  const player = useVideoPlayer(compressedUri ?? '', (p) => {
    p.loop = true;
  });

  useEffect(() => {
    if (Platform.OS !== 'ios') return;

    try {
      const expoGlobal = (global as any).expo;
      const modules = expoGlobal?.modules ?? {};
      console.log('Expo native modules keys:', Object.keys(modules));

      const mod = requireNativeModule<any>('BayutVideoCompressor');
      console.log('BayutVideoCompressor via requireNativeModule:', mod);
    } catch (e) {
      console.log('requireNativeModule BayutVideoCompressor failed with:', e);
    }
  }, []);

  const pickVideo = async () => {
    try {
      const permResult = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!permResult.granted) {
        Alert.alert('Permission needed', 'Please grant access to your video library');
        return;
      }

      const pickerResult = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['videos'],
        quality: 1,
      });

      if (!pickerResult.canceled && pickerResult.assets[0]) {
        const uri = pickerResult.assets[0].uri;
        setSelectedVideo(uri);
        setResult(null);
        setError(null);
        setCompressedUri(null);
        setProgress(0);

        // Get metadata
        try {
          const meta = await getMetadata(uri);
          setVideoMeta(
            `${meta.width}x${meta.height} • ${meta.duration.toFixed(1)}s • ${formatBytes(meta.size)}`
          );
        } catch (e) {
          setVideoMeta('Could not read metadata');
        }
      }
    } catch (e: any) {
      setError(`Pick failed: ${e.message}`);
    }
  };

  const startCompression = async () => {
    if (!selectedVideo) return;

    setCompressing(true);
    setProgress(0);
    setResult(null);
    setError(null);

    const options: CompressOptions = {
      videoMaxDimensionLongEdge: selectedMaxSize,
      videoCodec: selectedCodec,
      speed: selectedSpeed,
      getCancellationId: (id) => {
        cancellationId.current = id;
        setCurrentUuid(id);
      },
    };

    const startTime = Date.now();

    try {
      // Get input size
      const inputMeta = await getMetadata(selectedVideo);
      const inputSize = inputMeta.size;

      const outputUri = await compressVideo(selectedVideo, options, (p) => {
        console.log(`Compression progress: ${Math.round(p * 100)}% (Session: ${cancellationId.current})`);
        setProgress(p);
      });

      const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);

      // Get output size
      const outputMeta = await getMetadata(outputUri);
      const outputSize = outputMeta.size;

      const ratio = inputSize > 0 ? ((1 - outputSize / inputSize) * 100).toFixed(1) : '0';

      setResult({
        inputSize: formatBytes(inputSize),
        outputSize: formatBytes(outputSize),
        ratio: `${ratio}%`,
        duration: `${elapsed}s`,
        outputUri,
      });
      setCompressedUri(outputUri);
    } catch (e: any) {
      setError(e.message || 'Compression failed');
    } finally {
      setCompressing(false);
      setProgress(0);
      cancellationId.current = null;
    }
  };

  const cancelCompression = () => {
    if (cancellationId.current) {
      cancel(cancellationId.current);
    }
  };

  const pickImage = async () => {
    try {
      const permResult = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!permResult.granted) {
        Alert.alert('Permission needed', 'Please grant access to your photo library');
        return;
      }

      const pickerResult = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['images'],
        quality: 1,
      });

      if (!pickerResult.canceled && pickerResult.assets[0]) {
        setSelectedImage(pickerResult.assets[0].uri);
        setImageResult(null);
        setImageError(null);
      }
    } catch (e: any) {
      setImageError(`Pick failed: ${e.message}`);
    }
  };

  const startImageCompression = async () => {
    if (!selectedImage) return;
    setImageCompressing(true);
    setImageResult(null);
    setImageError(null);

    try {
      const inputInfo = await fetch(selectedImage);
      const inputBlob = await inputInfo.blob();
      const inputSize = inputBlob.size;

      const outputUri = await compressImage(selectedImage, {
        maxWidth: 1080,
        maxHeight: 1920,
        quality: 0.8,
      });

      const outputInfo = await fetch(outputUri);
      const outputBlob = await outputInfo.blob();
      const outputSize = outputBlob.size;

      const ratio = inputSize > 0 ? ((1 - outputSize / inputSize) * 100).toFixed(1) : '0';

      setImageResult({
        inputSize: formatBytes(inputSize),
        outputSize: formatBytes(outputSize),
        ratio: `${ratio}%`,
        outputUri,
      });
    } catch (e: any) {
      setImageError(e.message || 'Image compression failed');
    } finally {
      setImageCompressing(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        <Text style={styles.title}>🎬 Video Compressor</Text>
        <Text style={styles.subtitle}>Hardware-Accelerated</Text>

        {/* Video Picker */}
        <TouchableOpacity style={styles.pickButton} onPress={pickVideo} disabled={compressing}>
          <Text style={styles.pickButtonText}>
            {selectedVideo ? '📹 Change Video' : '📹 Pick Video'}
          </Text>
        </TouchableOpacity>

        {selectedVideo && (
          <View style={styles.metaCard}>
            <Text style={styles.metaLabel}>Selected Video</Text>
            <Text style={styles.metaValue}>{videoMeta}</Text>
          </View>
        )}

        {/* Options */}
        {selectedVideo && (
          <View style={styles.optionsCard}>
            <Text style={styles.optionsTitle}>Compression Settings</Text>

            {/* Codec */}
            <Text style={styles.optionLabel}>Codec</Text>
            <View style={styles.optionRow}>
              {(['h264', 'hevc'] as const).map((codec) => (
                <TouchableOpacity
                  key={codec}
                  style={[styles.optionPill, selectedCodec === codec && styles.optionPillActive]}
                  onPress={() => setSelectedCodec(codec)}
                  disabled={compressing}
                >
                  <Text style={[styles.optionPillText, selectedCodec === codec && styles.optionPillTextActive]}>
                    {codec === 'h264' ? 'H.264' : 'H.265/HEVC'}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>

            {/* Speed */}
            <Text style={styles.optionLabel}>Speed</Text>
            <View style={styles.optionRow}>
              {(['ultrafast', 'fast', 'balanced'] as const).map((speed) => (
                <TouchableOpacity
                  key={speed}
                  style={[styles.optionPill, selectedSpeed === speed && styles.optionPillActive]}
                  onPress={() => setSelectedSpeed(speed)}
                  disabled={compressing}
                >
                  <Text style={[styles.optionPillText, selectedSpeed === speed && styles.optionPillTextActive]}>
                    {speed === 'ultrafast' ? '⚡ Ultra' : speed === 'fast' ? '🏃 Fast' : '⚖️ Balanced'}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>

            {/* Max Size */}
            <Text style={styles.optionLabel}>Max Resolution</Text>
            <View style={styles.optionRow}>
              {[720, 1080, 1920].map((size) => (
                <TouchableOpacity
                  key={size}
                  style={[styles.optionPill, selectedMaxSize === size && styles.optionPillActive]}
                  onPress={() => setSelectedMaxSize(size)}
                  disabled={compressing}
                >
                  <Text style={[styles.optionPillText, selectedMaxSize === size && styles.optionPillTextActive]}>
                    {size}p
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>
        )}

        {/* Compress Button */}
        {selectedVideo && !compressing && (
          <TouchableOpacity style={styles.compressButton} onPress={startCompression}>
            <Text style={styles.compressButtonText}>🚀 Compress</Text>
          </TouchableOpacity>
        )}

        {/* Progress */}
        {compressing && (
          <View style={styles.progressCard}>
            <View style={styles.progressHeader}>
              <View>
                <Text style={styles.progressTitle}>Compressing...</Text>
                <Text style={{ color: '#8E8E93', fontSize: 10, marginTop: 2 }}>ID: {currentUuid}</Text>
              </View>
              <TouchableOpacity onPress={cancelCompression}>
                <Text style={styles.cancelText}>Cancel</Text>
              </TouchableOpacity>
            </View>
            <View style={styles.progressBarBg}>
              <View style={[styles.progressBarFill, { width: `${Math.round(progress * 100)}%` }]} />
            </View>
            <Text style={styles.progressPercent}>{Math.round(progress * 100)}%</Text>
            <ActivityIndicator style={{ marginTop: 8 }} color="#007AFF" />
          </View>
        )}

        {/* Result */}
        {result && (
          <View style={styles.resultCard}>
            <Text style={styles.resultTitle}>✅ Compression Complete!</Text>

            <View style={styles.resultRow}>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Input</Text>
                <Text style={styles.resultValue}>{result.inputSize}</Text>
              </View>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Output</Text>
                <Text style={styles.resultValue}>{result.outputSize}</Text>
              </View>
            </View>

            <View style={styles.resultRow}>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Reduced</Text>
                <Text style={[styles.resultValue, styles.resultHighlight]}>{result.ratio}</Text>
              </View>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Time</Text>
                <Text style={[styles.resultValue, styles.resultHighlight]}>{result.duration}</Text>
              </View>
            </View>

            <View style={styles.resultRow}>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Codec</Text>
                <Text style={styles.resultValue}>{selectedCodec.toUpperCase()}</Text>
              </View>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Speed</Text>
                <Text style={styles.resultValue}>{selectedSpeed}</Text>
              </View>
            </View>
          </View>
        )}

        {/* Compressed Video Preview */}
        {compressedUri && result && (
          <View style={styles.videoCard}>
            <Text style={styles.videoCardTitle}>🎥 Compressed Video</Text>
            <VideoView
              player={player}
              style={styles.videoPlayer}
              allowsFullscreen
              allowsPictureInPicture
            />
            <TouchableOpacity
              style={styles.playButton}
              onPress={() => {
                if (player) {
                  player.play();
                }
              }}
            >
              <Text style={styles.playButtonText}>▶ Play</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* Error */}
        {error && (
          <View style={styles.errorCard}>
            <Text style={styles.errorTitle}>❌ Error</Text>
            <Text style={styles.errorMessage}>{error}</Text>
          </View>
        )}

        {/* ── Image Compression Section ── */}
        <View style={{ height: 40, borderTopWidth: 1, borderTopColor: '#2C2C2E', marginTop: 20 }} />
        <Text style={styles.title}>🖼️ Image Compressor</Text>
        <Text style={styles.subtitle}>Resize & Quality Control</Text>

        <TouchableOpacity style={styles.pickButton} onPress={pickImage} disabled={imageCompressing}>
          <Text style={styles.pickButtonText}>
            {selectedImage ? '🖼️ Change Image' : '🖼️ Pick Image'}
          </Text>
        </TouchableOpacity>

        {selectedImage && (
          <View style={[styles.metaCard, { alignItems: 'center' }]}>
            <Image source={{ uri: selectedImage }} style={{ width: 200, height: 200, borderRadius: 12, marginBottom: 8 }} resizeMode="contain" />
            <Text style={styles.metaLabel}>Selected Image</Text>
          </View>
        )}

        {selectedImage && !imageCompressing && (
          <TouchableOpacity style={styles.compressButton} onPress={startImageCompression}>
            <Text style={styles.compressButtonText}>🚀 Compress Image</Text>
          </TouchableOpacity>
        )}

        {imageCompressing && (
          <View style={styles.progressCard}>
            <Text style={styles.progressTitle}>Compressing image...</Text>
            <ActivityIndicator style={{ marginTop: 8 }} color="#007AFF" />
          </View>
        )}

        {imageResult && (
          <View style={styles.resultCard}>
            <Text style={styles.resultTitle}>✅ Image Compressed!</Text>
            <View style={styles.resultRow}>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Input</Text>
                <Text style={styles.resultValue}>{imageResult.inputSize}</Text>
              </View>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Output</Text>
                <Text style={styles.resultValue}>{imageResult.outputSize}</Text>
              </View>
              <View style={styles.resultItem}>
                <Text style={styles.resultLabel}>Reduced</Text>
                <Text style={[styles.resultValue, styles.resultHighlight]}>{imageResult.ratio}</Text>
              </View>
            </View>
            <Image source={{ uri: imageResult.outputUri }} style={{ width: '100%', height: 250, borderRadius: 12, marginTop: 12 }} resizeMode="contain" />
          </View>
        )}

        {imageError && (
          <View style={styles.errorCard}>
            <Text style={styles.errorTitle}>❌ Error</Text>
            <Text style={styles.errorMessage}>{imageError}</Text>
          </View>
        )}

        <View style={{ height: 40 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0A0A0F',
  },
  scrollContent: {
    padding: 20,
  },
  title: {
    fontSize: 32,
    fontWeight: '800',
    color: '#FFFFFF',
    textAlign: 'center',
    marginTop: 20,
  },
  subtitle: {
    fontSize: 14,
    color: '#8E8E93',
    textAlign: 'center',
    marginBottom: 30,
    letterSpacing: 1,
  },
  pickButton: {
    backgroundColor: '#1C1C1E',
    borderRadius: 16,
    padding: 20,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#2C2C2E',
    borderStyle: 'dashed',
  },
  pickButtonText: {
    color: '#FFFFFF',
    fontSize: 18,
    fontWeight: '600',
  },
  metaCard: {
    backgroundColor: '#1C1C1E',
    borderRadius: 12,
    padding: 16,
    marginTop: 12,
  },
  metaLabel: {
    color: '#8E8E93',
    fontSize: 12,
    marginBottom: 4,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  metaValue: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '500',
  },
  optionsCard: {
    backgroundColor: '#1C1C1E',
    borderRadius: 16,
    padding: 20,
    marginTop: 16,
  },
  optionsTitle: {
    color: '#FFFFFF',
    fontSize: 18,
    fontWeight: '700',
    marginBottom: 16,
  },
  optionLabel: {
    color: '#8E8E93',
    fontSize: 12,
    marginBottom: 8,
    marginTop: 12,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  optionRow: {
    flexDirection: 'row',
    gap: 8,
  },
  optionPill: {
    flex: 1,
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 10,
    backgroundColor: '#2C2C2E',
    alignItems: 'center',
  },
  optionPillActive: {
    backgroundColor: '#007AFF',
  },
  optionPillText: {
    color: '#8E8E93',
    fontSize: 13,
    fontWeight: '600',
  },
  optionPillTextActive: {
    color: '#FFFFFF',
  },
  compressButton: {
    backgroundColor: '#007AFF',
    borderRadius: 16,
    padding: 18,
    alignItems: 'center',
    marginTop: 20,
  },
  compressButtonText: {
    color: '#FFFFFF',
    fontSize: 20,
    fontWeight: '700',
  },
  progressCard: {
    backgroundColor: '#1C1C1E',
    borderRadius: 16,
    padding: 20,
    marginTop: 20,
  },
  progressHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  progressTitle: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
  },
  cancelText: {
    color: '#FF3B30',
    fontSize: 14,
    fontWeight: '600',
  },
  progressBarBg: {
    height: 8,
    backgroundColor: '#2C2C2E',
    borderRadius: 4,
    overflow: 'hidden',
  },
  progressBarFill: {
    height: '100%',
    backgroundColor: '#007AFF',
    borderRadius: 4,
  },
  progressPercent: {
    color: '#8E8E93',
    textAlign: 'center',
    marginTop: 8,
    fontSize: 14,
  },
  resultCard: {
    backgroundColor: '#1C2E1C',
    borderRadius: 16,
    padding: 20,
    marginTop: 20,
    borderWidth: 1,
    borderColor: '#2E5E2E',
  },
  resultTitle: {
    color: '#30D158',
    fontSize: 18,
    fontWeight: '700',
    marginBottom: 16,
    textAlign: 'center',
  },
  resultRow: {
    flexDirection: 'row',
    marginBottom: 12,
    gap: 12,
  },
  resultItem: {
    flex: 1,
    backgroundColor: '#0A0A0F',
    borderRadius: 10,
    padding: 12,
    alignItems: 'center',
  },
  resultLabel: {
    color: '#8E8E93',
    fontSize: 11,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginBottom: 4,
  },
  resultValue: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '700',
  },
  resultHighlight: {
    color: '#30D158',
  },
  videoCard: {
    backgroundColor: '#1C1C1E',
    borderRadius: 16,
    padding: 16,
    marginTop: 16,
    alignItems: 'center',
  },
  videoCardTitle: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 12,
  },
  videoPlayer: {
    width: '100%',
    height: 300,
    borderRadius: 12,
    backgroundColor: '#000',
  },
  playButton: {
    backgroundColor: '#007AFF',
    borderRadius: 10,
    paddingVertical: 10,
    paddingHorizontal: 24,
    marginTop: 12,
  },
  playButtonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '600',
  },
  errorCard: {
    backgroundColor: '#2E1C1C',
    borderRadius: 16,
    padding: 20,
    marginTop: 20,
    borderWidth: 1,
    borderColor: '#5E2E2E',
  },
  errorTitle: {
    color: '#FF3B30',
    fontSize: 18,
    fontWeight: '700',
    marginBottom: 8,
  },
  errorMessage: {
    color: '#FF6B6B',
    fontSize: 14,
  },
});
