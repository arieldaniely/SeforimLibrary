package io.github.kdroidfilter.seforimlibrary.search

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import co.touchlab.kermit.Logger
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Produces L2-normalized 256-d Round 2 embeddings for Hebrew/Aramaic text on the JVM.
 *
 * The ONNX graph bakes in pooling + projection + L2 normalization, so [embed] returns
 * a vector ready for a Lucene `KnnFloatVectorField` (cosine). Query text is normalized
 * with [Round2Normalizer] to match the training distribution.
 *
 * Model artifacts (from the SeforimEmbedding release):
 *  - `seforim-embed-round2-int8.onnx`  (the model)
 *  - `tokenizer.json`              (the matching tokenizer)
 * Place both in a directory and point to it via `-DseforimEmbedModelDir=…`,
 * the `SEFORIM_EMBED_MODEL` env var, or one of the default candidate locations.
 * If not found, [tryLoad] returns null and dense search is simply disabled.
 */
class SeforimEmbedder private constructor(
    onnxModel: Path,
    tokenizerJson: Path,
    private val maxLen: Int = 256,
) : Closeable {

    val dim: Int = 256

    private val tokenizer: HuggingFaceTokenizer = HuggingFaceTokenizer.newInstance(tokenizerJson)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = openSession(env, onnxModel)

    // The stock Maven `onnxruntime` artifact is CPU-only on desktop JVM. Hardware EPs only
    // work if a capable native is bundled: CUDA (swap to the onnxruntime_gpu artifact +
    // system CUDA/cuDNN, NVIDIA), or DirectML/CoreML (custom ORT build). They're opt-in via
    // -DseforimEmbedGpu=true / SEFORIM_EMBED_GPU=1; otherwise an optimized CPU session
    // (full graph opt + all cores). int8 is already CPU-friendly.
    private fun openSession(env: OrtEnvironment, model: Path): OrtSession {
        val wantGpu = System.getProperty("seforimEmbedGpu")?.toBoolean() == true ||
            System.getenv("SEFORIM_EMBED_GPU")?.toBoolean() == true
        if (wantGpu) {
            val eps = listOf<Pair<String, (OrtSession.SessionOptions) -> Unit>>(
                "CUDA" to { it.addCUDA(0) },
                "DirectML" to { it.addDirectML(0) },
                "CoreML" to { it.addCoreML() },
            )
            for ((name, add) in eps) {
                runCatching {
                    val o = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); add(this)
                    }
                    return env.createSession(model.toString(), o).also { logger.i { "ONNX EP: $name (GPU)" } }
                }
            }
            logger.i { "No GPU EP available in this build; using CPU" }
        }
        val o = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            runCatching { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors()) }
        }
        return env.createSession(model.toString(), o)
    }

    enum class Role(val prefix: String) { QUERY("[QUERY]"), PASSAGE("[PASSAGE]") }

    /** Encode a query or corpus passage using the same text contract. */
    fun embed(text: String, role: Role = Role.QUERY): FloatArray {
        val enc = tokenizer.encode("${role.prefix} ${Round2Normalizer.clean(text)}")
        var ids = enc.ids
        var mask = enc.attentionMask
        if (ids.size > maxLen) {
            ids = ids.copyOf(maxLen)
            mask = mask.copyOf(maxLen)
        }
        OnnxTensor.createTensor(env, arrayOf(ids)).use { idsT ->
            OnnxTensor.createTensor(env, arrayOf(mask)).use { maskT ->
                session.run(mapOf("input_ids" to idsT, "attention_mask" to maskT)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    return (res[0].value as Array<FloatArray>)[0].also { vector ->
                        require(vector.size == dim && vector.all { it.isFinite() }) {
                            "Round 2 model returned an invalid embedding"
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }

    companion object {
        private val logger = Logger.withTag("SeforimEmbedder")

        /**
         * Locate the model and load an embedder, or return null if unavailable
         * (dense search then degrades gracefully to lexical-only).
         */
        private fun candidateDirs(explicitDir: Path?): List<Path> = listOfNotNull(
            explicitDir,
            System.getProperty("seforimEmbedModelDir")?.let { Path.of(it) },
            System.getenv("SEFORIM_EMBED_MODEL")?.let { Path.of(it) },
        ).distinct()

        /** Cheap presence check (model + tokenizer files) WITHOUT creating the heavy
         *  OrtSession — lets callers decide to take the dense path then load lazily. */
        fun isAvailable(explicitDir: Path? = null): Boolean =
            candidateDirs(explicitDir).any { findOnnx(it) != null && findTokenizer(it) != null }

        fun tryLoad(explicitDir: Path? = null): SeforimEmbedder? {
            val dirs = candidateDirs(explicitDir)
            for (dir in dirs) {
                val onnx = findOnnx(dir) ?: continue
                val tok = findTokenizer(dir) ?: continue
                return runCatching {
                    logger.i { "Loading dense embedder: onnx=$onnx tokenizer=$tok" }
                    SeforimEmbedder(onnx, tok)
                }.onFailure { logger.w(it) { "Failed to load embedder from $dir" } }.getOrNull()
            }
            logger.i { "No embedding model found; dense search disabled. Checked: $dirs" }
            return null
        }

        // Only accept Round 2 artifacts. A model with another tokenizer or dimension
        // cannot safely query an index built from this checkpoint.
        private fun findOnnx(dir: Path): Path? = listOf(
            "seforim-embed-round2-int8.onnx", "seforim-embed-round2-fp32.onnx",
        ).map { dir.resolve(it) }.firstOrNull { Files.isRegularFile(it) }

        private fun findTokenizer(dir: Path): Path? = dir.resolve("tokenizer.json").takeIf { Files.isRegularFile(it) }
    }
}
