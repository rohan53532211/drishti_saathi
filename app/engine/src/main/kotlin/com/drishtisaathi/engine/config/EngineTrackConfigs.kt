package com.drishtisaathi.engine.config

import kotlinx.serialization.Serializable

/**
 * Track A: viewing distance from iris size, card-scale correction.
 * Owner: Track A (engine). Replace this placeholder with a data class whose fields match the "distance" section of
 * assets/rules.json (strict parsing: every key must exist here). Edit only this file and that section.
 */
@Serializable class DistanceConfig

/** Track A: Bayesian SE band (priors, likelihood sigma, grid). Section "bayes". */
@Serializable class BayesConfig

/** Track A: landmark indices and face-shape ratio thresholds. Section "faceGeometry". */
@Serializable class FaceGeometryConfig
