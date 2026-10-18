package com.cellscope.drive.analytics.anomaly

import android.content.Context
import org.json.JSONObject
import java.io.InputStreamReader
import kotlin.math.ln
import kotlin.math.pow

data class AnomalyScoreResult(
    val anomalyScore: Double,          // 0.0 .. 1.0 (higher = more anomalous)
    val isAnomaly: Boolean,            // anomalyScore >= threshold (typically 0.60)
    val averagePathLength: Double,
    val primaryContributingFeature: String
)

class ForestAnomalyScorer(
    private val maxSamples: Int = 256,
    private val decisionThreshold: Double = 0.58
) {
    data class DecisionTree(
        val childrenLeft: IntArray,
        val childrenRight: IntArray,
        val feature: IntArray,
        val threshold: DoubleArray,
        val nodeSamples: IntArray
    )

    private val trees = mutableListOf<DecisionTree>()
    private val featureNames = listOf("RSRP", "RSRQ", "SINR", "Speed", "Delta RSRP", "Sample dt")
    private val cMaxSamples = computeAveragePathLength(maxSamples.toDouble())

    init {
        // Build fallback lightweight trees in case asset is not initialized
        if (trees.isEmpty()) {
            buildDefaultFallbackTrees()
        }
    }

    fun loadFromJson(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            val treesArray = root.getJSONArray("trees")
            val loadedTrees = mutableListOf<DecisionTree>()

            for (i in 0 until treesArray.length()) {
                val tObj = treesArray.getJSONObject(i)
                val left = tObj.getJSONArray("children_left")
                val right = tObj.getJSONArray("children_right")
                val feat = tObj.getJSONArray("feature")
                val thresh = tObj.getJSONArray("threshold")
                val samples = tObj.getJSONArray("n_node_samples")

                val len = left.length()
                val leftArr = IntArray(len) { left.getInt(it) }
                val rightArr = IntArray(len) { right.getInt(it) }
                val featArr = IntArray(len) { feat.getInt(it) }
                val threshArr = DoubleArray(len) { thresh.getDouble(it) }
                val sampleArr = IntArray(len) { samples.getInt(it) }

                loadedTrees.add(DecisionTree(leftArr, rightArr, featArr, threshArr, sampleArr))
            }

            if (loadedTrees.isNotEmpty()) {
                trees.clear()
                trees.addAll(loadedTrees)
            }
        } catch (e: Exception) {
            // Keep fallback trees
        }
    }

    fun score(
        rsrpDbm: Double,
        rsrqDb: Double,
        sinrDb: Double,
        speedKmh: Double,
        deltaRsrp: Double,
        sampleDt: Double
    ): AnomalyScoreResult {
        val x = doubleArrayOf(rsrpDbm, rsrqDb, sinrDb, speedKmh, deltaRsrp, sampleDt)
        var totalPathLength = 0.0
        val featureSplitDepths = DoubleArray(featureNames.size) { 0.0 }
        val featureHitCounts = IntArray(featureNames.size) { 0 }

        for (tree in trees) {
            var node = 0
            var depth = 0

            while (tree.childrenLeft[node] != -1) {
                val fIdx = tree.feature[node]
                val tVal = tree.threshold[node]

                if (fIdx in featureNames.indices) {
                    featureSplitDepths[fIdx] += depth
                    featureHitCounts[fIdx]++
                }

                node = if (x[fIdx] <= tVal) {
                    tree.childrenLeft[node]
                } else {
                    tree.childrenRight[node]
                }
                depth++
            }

            val nSamples = tree.nodeSamples[node]
            val pathLen = depth + computeAveragePathLength(nSamples.toDouble())
            totalPathLength += pathLen
        }

        val avgPathLength = totalPathLength / trees.size.coerceAtLeast(1)
        val exponent = -(avgPathLength / cMaxSamples)
        val rawScore = 2.0.pow(exponent)
        val normalizedScore = rawScore.coerceIn(0.0, 1.0)

        // Find feature with lowest average split depth (isolated sample fastest)
        var primaryFeature = "Multi-variate interaction"
        var minAvgDepth = Double.MAX_VALUE
        for (i in featureNames.indices) {
            if (featureHitCounts[i] > 0) {
                val avgD = featureSplitDepths[i] / featureHitCounts[i]
                if (avgD < minAvgDepth) {
                    minAvgDepth = avgD
                    primaryFeature = featureNames[i]
                }
            }
        }

        return AnomalyScoreResult(
            anomalyScore = (normalizedScore * 1000.0).toInt() / 1000.0,
            isAnomaly = normalizedScore >= decisionThreshold,
            averagePathLength = (avgPathLength * 100.0).toInt() / 100.0,
            primaryContributingFeature = primaryFeature
        )
    }

    private fun buildDefaultFallbackTrees() {
        // Built-in calibrated trees to guarantee immediate evaluation without disk IO
        val t1 = DecisionTree(
            childrenLeft = intArrayOf(1, 3, -1, -1, -1),
            childrenRight = intArrayOf(2, 4, -1, -1, -1),
            feature = intArrayOf(0, 4, -2, -2, -2), // RSRP, Delta RSRP
            threshold = doubleArrayOf(-115.0, -18.0, -2.0, -2.0, -2.0),
            nodeSamples = intArrayOf(256, 32, 224, 8, 24)
        )
        val t2 = DecisionTree(
            childrenLeft = intArrayOf(1, -1, 3, -1, -1),
            childrenRight = intArrayOf(2, -1, 4, -1, -1),
            feature = intArrayOf(5, -2, 2, -2, -2), // Sample dt, SINR
            threshold = doubleArrayOf(8.0, -2.0, -4.0, -2.0, -2.0),
            nodeSamples = intArrayOf(256, 15, 241, 12, 229)
        )
        val t3 = DecisionTree(
            childrenLeft = intArrayOf(1, 3, -1, -1, -1),
            childrenRight = intArrayOf(2, 4, -1, -1, -1),
            feature = intArrayOf(4, 1, -2, -2, -2), // Delta RSRP, RSRQ
            threshold = doubleArrayOf(-20.0, -16.0, -2.0, -2.0, -2.0),
            nodeSamples = intArrayOf(256, 20, 236, 6, 14)
        )
        trees.addAll(listOf(t1, t2, t3))
    }

    companion object {
        private const val EULER_MASCHERONI = 0.5772156649

        fun computeAveragePathLength(n: Double): Double {
            return when {
                n > 2.0 -> 2.0 * (ln(n - 1.0) + EULER_MASCHERONI) - (2.0 * (n - 1.0) / n)
                n == 2.0 -> 1.0
                else -> 0.0
            }
        }

        fun loadFromAssets(context: Context): ForestAnomalyScorer {
            val scorer = ForestAnomalyScorer()
            try {
                context.assets.open("isolation_forest_model.json").use { stream ->
                    val jsonStr = InputStreamReader(stream).readText()
                    scorer.loadFromJson(jsonStr)
                }
            } catch (e: Exception) {
                // Fallback initialized in constructor
            }
            return scorer
        }
    }
}
