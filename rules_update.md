# Simulation-tuned constants for rules.json

Source: tools/far_point_simulation.py (Monte Carlo, 40,000 simulated eyes). Full results are in the spec doc, Section 8.

Add or update these in assets/rules.json and use them in the engine:

```json
"farPoint": {
  "selfHeldReachM": 0.60,
  "staffHeldMaxM": 2.0,
  "irisDistanceMaxM": 0.60,
  "beyondIrisUse": "CARD_CALIBRATED_PD",
  "blueLcaD": 0.67,
  "whiteMaxDistanceM": 0.80,
  "consistencyThresholdD": 0.50,
  "consistencyAction": "RETAKE_ONCE_THEN_LOW_CONFIDENCE",
  "repeatSpreadThresholdD": 0.50,
  "repeatSpreadAction": "THIRD_READING_MEDIAN"
},
"bayes": {
  "likelihoodSigmaD": 0.80,
  "gridStepD": 0.25,
  "bandWidthD": 1.0,
  "priorIndiaAdults30plus": { "myopia": 0.277, "hyperopia": 0.229, "emmetropia": 0.494 },
  "myopiaMagnitudePrior": { "type": "EXPONENTIAL", "meanD": 1.5, "note": "assumption, replace with pilot data" },
  "minBandProbabilityToShow": 0.60
},
"pd": {
  "sdNoCardMm": 3.3,
  "sdCardMm": 1.2,
  "frameFitClaimsRequireCard": true
},
"add": {
  "verifyWithReadyReader": true
}
```

Behaviour rules:
1. If the customer cannot reach the blur point at 0.6 m, show "Staff: hold the phone and slowly move it away from the customer" (Luo method).
2. Beyond 0.6 m, compute distance from the card-calibrated PD (pixel distance between irises), not from iris diameter, because the iris is only a few pixels wide there.
3. If blue and white SE differ by more than 0.50 D, retake once. If they still differ, mark low confidence.
4. If two repeats of the same colour differ by more than 0.50 D, take a third reading and use the median.
5. Bayesian band uses sigma 0.8 D, from the real studies, not the optimistic simulated value.
6. Without card calibration, show PD as "about X mm (±3 mm)" and never say a frame "fits" based on it.
7. Every reading-add suggestion says "confirm with a ready-reader at the counter".

Add unit tests for rules 3, 4 and 6.
