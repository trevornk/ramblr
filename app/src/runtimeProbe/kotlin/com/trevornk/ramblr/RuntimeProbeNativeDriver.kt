package com.trevornk.ramblr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File

/** Target-side implementation: no production API ABI crosses the separately optimized test DEX. */
object RuntimeProbeNativeDriver {
    private const val ASR_ARCHIVE = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
    private const val FRAME = 512
    private const val TAG = "NativeProbeTarget"

    @JvmStatic fun run(context: Context, stage: String) {
        when (stage) {
            "harness" -> Log.i(TAG, "TARGET_BOUNDARY_OK")
            "voiceIme" -> voiceIme(context)
            "provision" -> provision(context)
            "asr" -> asr(context)
            "vad" -> vad(context)
            "cleanup" -> cleanup(context)
            else -> error("unexpected target probe stage $stage")
        }
    }

    private fun voiceIme(context: Context) {
        val info = context.getSystemService(InputMethodManager::class.java).inputMethodList.single {
            it.packageName == context.packageName && it.serviceInfo.name == "com.trevornk.ramblr.RamblrImeService"
        }
        check(info.subtypeCount == 1)
        val subtype = info.getSubtypeAt(0)
        check(subtype.mode == "voice" && subtype.locale.isEmpty() && !subtype.isAuxiliary && !subtype.overridesImplicitlyEnabledSubtype())
        Log.i(TAG, "VOICE_IME_OK")
    }

    private fun provision(context: Context) {
        check(context.packageName == "com.trevornk.ramblr.r8probe9")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val records = JSONArray()
        val models = listOf(SILERO_VAD_MODEL, MODEL_CATALOG.first { it.archive == ASR_ARCHIVE }, MUMBLE_CLEANUP_Q4_0_MODEL)
        models.forEach { model ->
            ModelDownloader.delete(context, model)
            val deadline = SystemClock.elapsedRealtime() + when (model.archive) {
                SILERO_VAD_MODEL.archive -> 120_000L
                ASR_ARCHIVE -> 420_000L
                else -> 840_000L
            }
            var done = false; var next = 5
            Log.i(TAG, "MODEL_DOWNLOAD_START archive=${model.archive}")
            ModelDownloader.download(context, model, { SystemClock.elapsedRealtime() >= deadline }) { state ->
                when (state) {
                    is DownloadState.Downloading -> { val p=(state.progress*100).toInt(); if (p >= next || p == 100) { Log.i(TAG,"MODEL_DOWNLOAD_PROGRESS archive=${model.archive} percent=$p"); next=((p/5)+1)*5 } }
                    DownloadState.Extracting -> Log.i(TAG,"MODEL_EXTRACT_START archive=${model.archive}")
                    DownloadState.Done -> done = true
                    is DownloadState.Error -> throw AssertionError("${model.archive}: ${state.message}", state.cause)
                }
            }
            check(done && ModelDownloader.isInstalledDir(ModelDownloader.modelDir(context, model))) { "provision failed ${model.archive}" }
            records.put(JSONObject().put("archive",model.archive).put("sha256",model.sha256))
        }
        val asr = MODEL_CATALOG.first { it.archive == ASR_ARCHIVE }
        val bench = File(context.filesDir, "bench_models/${asr.archive}"); bench.deleteRecursively()
        check(ModelDownloader.modelDir(context, asr).copyRecursively(bench, overwrite=true))
        check(File(bench,"test_wavs").listFiles { f -> f.extension.equals("wav",true) }?.isNotEmpty() == true)
        context.getSharedPreferences("ramblr",Context.MODE_PRIVATE).edit().putString("local_cleanup_model_name",MUMBLE_CLEANUP_Q4_0_MODEL.archive).apply()
        File(context.filesDir,"native_probe_provisioning.json").writeText(JSONObject().put("models",records).toString())
        Log.i(TAG,"PROVISIONED models=${records.length()}")
    }

    private fun asr(context: Context) {
        val dir=File(context.filesDir,"bench_models/$ASR_ARCHIVE"); val wav=File(dir,"test_wavs").listFiles { f->f.extension.equals("wav",true) }?.sortedBy { it.name }?.firstOrNull() ?: error("missing ASR wav")
        val config=LocalTranscriber.detectModelConfig(dir,2) ?: error("no ASR config")
        val recognizer=com.k2fsa.sherpa.onnx.OfflineRecognizer(null,config)
        val results=JSONArray()
        try { repeat(3) { i ->
            val stream=recognizer.createStream(); val start=SystemClock.elapsedRealtime()
            try { stream.acceptWaveform(readWav(wav),16000); recognizer.decode(stream); results.put(JSONObject().put("run",i).put("decodeMs",SystemClock.elapsedRealtime()-start).put("text",recognizer.getResult(stream).text)) } finally { stream.release() }
        }} finally { recognizer.release() }
        check(results.length() == 3) { "ASR produced no decodes" }
        File(context.filesDir,"bench_results.json").writeText(JSONObject().put("wav",wav.name).put("decodes",results).toString())
        Log.i(TAG,"ASR_OK wav=${wav.name} decodes=${results.length()}")
    }

    private fun vad(context: Context) {
        val model=ModelDownloader.vadModelFile(context,SILERO_VAD_MODEL) ?: error("missing VAD model")
        val wav=File(context.filesDir,"bench_models/$ASR_ARCHIVE/test_wavs").listFiles { f->f.extension.equals("wav",true) }?.sortedBy { it.name }?.firstOrNull() ?: error("missing VAD wav")
        val vad=SherpaVadHandle.create(model) ?: error("VAD native create null"); val segments=JSONArray()
        vad.use { h ->
            val samples=readWav(wav); var off=0
            while(off<samples.size) { val frame=FloatArray(FRAME); val n=minOf(FRAME,samples.size-off);samples.copyInto(frame,0,off,off+n);h.acceptWaveform(frame);off+=n }
            repeat(64) { h.acceptWaveform(FloatArray(FRAME)) }; h.flush()
            while(!h.isEmpty()) { val s=h.front();segments.put(JSONObject().put("start",s.start).put("samples",s.samples.size));h.pop() }
        }
        check(segments.length()>0 && (0 until segments.length()).any { segments.getJSONObject(it).getInt("samples")>0 })
        File(context.filesDir,"native_probe_vad.json").writeText(JSONObject().put("wav",wav.name).put("segments",segments).toString())
        Log.i(TAG,"VAD_OK wav=${wav.name} segments=$segments")
    }

    private fun cleanup(context: Context) {
        val model=LocalCleanupProvider.selectedModel(context);val file=ModelDownloader.localCleanupModelFile(context,model) ?: error("missing cleanup model")
        val cases=listOf("we raised one point two million dollars last quarter","we saw like a twenty three percent increase","call me at five five five one two three four five six seven","the invoice total is four hundred fifty dollars","the meeting got moved to four thirty pm","she finished in twenty first place","there were three hundred forty two people at the event","the contract expires in twenty twenty six","the board is two point five inches thick","let's grab coffee tomorrow and talk about the project")
        var accepted=0;var escaped=0;val results=JSONArray()
        for(raw in cases) { val normalized=SpokenNumberNormalizer.normalize(raw);val started=SystemClock.elapsedRealtime();val r=RealLocalInferenceEngine.complete(LocalCleanupProvider.selectedSystemPrompt(context),normalized.text,file.absolutePath,System.currentTimeMillis()+60_000,{false});val row=JSONObject().put("raw",raw).put("latencyMs",SystemClock.elapsedRealtime()-started)
            if(r is LocalInferenceResult.Success) { val verdict=NumericPreservationVerifier.verify(normalized,r.text.trim());if(verdict is NumericPreservation.Rejected) row.put("outcome","REJECTED_NUMERIC_DIVERGENCE") else { accepted++;row.put("outcome","ACCEPTED"); if(!digitsSurvive(normalized.text,r.text)) escaped++ } } else row.put("outcome",r.javaClass.simpleName);results.put(row) }
        check(escaped==0 && accepted>0) { "cleanup accepted=$accepted escaped=$escaped" }
        File(context.filesDir,"numeric_cleanup_results.json").writeText(JSONObject().put("accepted",accepted).put("escaped",escaped).put("results",results).toString())
        listOf("native_probe_provisioning.json","bench_results.json","native_probe_vad.json","numeric_cleanup_results.json").forEach { check(File(context.filesDir,it).isFile) }
        listOf(SILERO_VAD_MODEL,MODEL_CATALOG.first { it.archive==ASR_ARCHIVE },MUMBLE_CLEANUP_Q4_0_MODEL).forEach { ModelDownloader.delete(context,it) }
        File(context.filesDir,"bench_models").deleteRecursively();context.getSharedPreferences("ramblr",Context.MODE_PRIVATE).edit().remove("local_cleanup_model_name").apply()
        Log.i(TAG,"CLEANUP_OK accepted=$accepted escaped=$escaped")
    }

    private fun digitsSurvive(input:String,out:String):Boolean { val wanted=Regex("[0-9][0-9,]*(?:\\.[0-9]+)?").findAll(input).map{it.value.replace(",","")}.toList();var i=0;for(g in Regex("[0-9][0-9,]*(?:\\.[0-9]+)?").findAll(out).map{it.value.replace(",","")})if(i<wanted.size&&g==wanted[i])i++;return i==wanted.size }
    private fun readWav(file: File): FloatArray = DataInputStream(file.inputStream().buffered()).use { input ->
        fun tag() = String(ByteArray(4).also { input.readFully(it) }, Charsets.US_ASCII)
        fun leInt() = input.read() or (input.read() shl 8) or (input.read() shl 16) or (input.read() shl 24)
        fun leShort() = input.read() or (input.read() shl 8)
        check(tag() == "RIFF"); leInt(); check(tag() == "WAVE")
        var channels = -1; var bits = -1
        while (true) {
            when (tag()) {
                "fmt " -> {
                    val size = leInt()
                    check(leShort() == 1); channels = leShort(); leInt(); leInt(); leShort(); bits = leShort()
                    input.skipBytes(size - 16)
                }
                "data" -> {
                    val size = leInt(); check(channels == 1 && bits == 16)
                    val bytes = ByteArray(size); input.readFully(bytes)
                    return@use FloatArray(bytes.size / 2) { i ->
                        (((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 255)).toShort().toInt() / 32768f)
                    }
                }
                else -> { val size = leInt(); input.skipBytes(size + (size and 1)) }
            }
        }
        error("unreachable")
    }
}
