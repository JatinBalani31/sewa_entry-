package org.sewaentry.tablet.face

object FaceModelContract {
    const val MODEL_VERSION = "mobilefacenet_112_v1"
    const val INPUT_SIZE = 112
    const val EMBEDDING_SIZE = 192
    const val CONSENT_VERSION = "sewa-face-consent-v1"
}

object FaceEnrollmentPolicy {
    const val MIN_SAMPLES = 3
    const val MAX_SAMPLES = 5
    const val DEFAULT_SAMPLES = 5

    fun isValidSampleCount(sampleCount: Int): Boolean =
        sampleCount in MIN_SAMPLES..MAX_SAMPLES
}
