# R8 reachability contract for release builds.
#
# These are not broad application/dependency keeps. They cover the exact Kotlin classes and
# members resolved by the two native libraries and the three github-only reflection entry points.
# Preserve names while still allowing code optimization; JNI and reflection bind by these names.

# Direct JNI entry points. The native libraries export Java_com_* symbols, so both their class
# names and external method names must remain stable.
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineRecognizer { native <methods>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineStream { native <methods>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineRecognizer { native <methods>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineStream { native <methods>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.Vad { native <methods>; }
-keep,allowoptimization class com.trevornk.ramblr.LlamaCppInference { native <methods>; }

# sherpa-onnx reads these config fields with GetFieldID and hard-coded descriptors. Keep only the
# offline/online/VAD configuration graph that Ramblr exposes to its vendored JNI binding.
-keep,allowoptimization class com.k2fsa.sherpa.onnx.FeatureConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.QnnConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.HomophoneReplacerConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineRecognizerConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineRecognizerConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineLMConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.EndpointConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.EndpointRule { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.VadModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.SileroVadModelConfig { <fields>; }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.TenVadModelConfig { <fields>; }

# JNI constructs these result objects by class name and constructor signature.
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OfflineRecognizerResult { <init>(...); }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.OnlineRecognizerResult { <init>(...); }
-keep,allowoptimization class com.k2fsa.sherpa.onnx.SpeechSegment { <init>(...); }

# Shared-source reflective lookups: only github contains these classes. Preserve exactly the
# public Kotlin object/companion members read by MainActivity and the github-only Activity name
# read by AdvancedActivity; storefront remains free to remove the whole feature.
-keep,allowoptimization class com.trevornk.ramblr.SelfUpdatePrefs {
    public static final com.trevornk.ramblr.SelfUpdatePrefs INSTANCE;
    public boolean isNotifyEnabled(android.content.Context);
}
-keep,allowoptimization class com.trevornk.ramblr.SelfUpdateCheckWorker {
    public static final com.trevornk.ramblr.SelfUpdateCheckWorker$Companion Companion;
}
-keep,allowoptimization class com.trevornk.ramblr.SelfUpdateCheckWorker$Companion {
    public void schedule(android.content.Context);
}
-keep,allowoptimization class com.trevornk.ramblr.SelfUpdateSettingsActivity
