import numpy as np, json
rng = np.random.default_rng(7)
N = 40000
LCA_MEAN, LCA_SD = 0.67, 0.11          # J Optom 2024
SIG_REL_NOCARD = 0.043 / 0.798         # MediaPipe mean abs 4.3% -> sigma
SIG_REL_CARD = 0.02                    # assumption with card calibration
SIG_JUDGE = 0.30                       # blur-point judgement per reading (D), assumption ~ depth of focus
SIG_BIAS = 0.25                        # person-level criterion bias shared across readings (D), assumption
GROSS_P, GROSS_D = 0.10, 1.25          # 10% readings misunderstood by +-1.25 D, assumption
MIN_D = 0.15

def far_point(S, lca, reach, sig_rel, bias, gross_mask):
    """one reading: returns measured distance or nan if no blur within reach"""
    V = S - lca                                    # true vergence of far point (negative)
    Vm = V + bias + rng.normal(0, SIG_JUDGE, S.size)
    Vm = Vm + gross_mask * rng.choice([-1, 1], S.size) * GROSS_D
    Vm = np.minimum(Vm, -1e-3)
    d = (1 / np.abs(Vm)) * (1 + rng.normal(0, sig_rel, S.size))
    d = np.where(d > reach, np.nan, np.maximum(d, MIN_D))
    return d

def run(reach, sig_rel, thr=0.5):
    S = rng.uniform(-6, -0.5, N)
    lca = rng.normal(LCA_MEAN, LCA_SD, N)
    bias = rng.normal(0, SIG_BIAS, N)
    reads_b, reads_w, gross_any = [], [], np.zeros(N, bool)
    for _ in range(2):
        gb = rng.random(N) < GROSS_P; gw = rng.random(N) < GROSS_P
        gross_any |= gb | gw
        reads_b.append(far_point(S, lca, reach, sig_rel, bias, gb))
        reads_w.append(far_point(S, 0.0, min(reach, 0.8), sig_rel, bias, gw))
    db = np.nanmean(np.vstack(reads_b), 0); dw = np.nanmean(np.vstack(reads_w), 0)
    se_b = -1 / db + LCA_MEAN; se_w = -1 / dw
    both = ~np.isnan(se_b) & ~np.isnan(se_w)
    diff = np.abs(se_b - se_w)
    flagged = both & (diff > thr)
    est = np.where(both, (se_b + se_w) / 2, se_b)
    return S, est, gross_any, both, diff, flagged

buckets = [(-1.0, -0.5), (-2.0, -1.0), (-4.0, -2.0), (-6.0, -4.0)]
res = {}
for mode, reach in [("self", 0.60), ("staff", 2.0)]:
    for card, sr in [("card", SIG_REL_CARD), ("nocard", SIG_REL_NOCARD)]:
        S, est, gross, both, diff, flagged = run(reach, sr)
        keep = ~np.isnan(est) & ~flagged
        row = {}
        for lo, hi in buckets:
            m = (S >= lo) & (S < hi)
            meas = m & ~np.isnan(est)
            k = m & keep
            err = est[k] - S[k]
            row[f"{hi}..{lo}"] = {"measurable_%": round(100 * meas.sum() / m.sum(), 1),
                                  "within_0.5D_%": round(100 * np.mean(np.abs(err) <= 0.5), 1),
                                  "MAE_D": round(np.mean(np.abs(err)), 2),
                                  "flagged_%": round(100 * flagged[m].sum() / max(meas.sum(), 1), 1)}
        res[f"{mode}_{card}"] = row

# consistency-check threshold ROC (staff, card)
roc = []
for thr in [0.25, 0.5, 0.75, 1.0, 1.25]:
    S, est, gross, both, diff, flagged = run(2.0, SIG_REL_CARD, thr)
    b = both
    tpr = (flagged & gross & b).sum() / (gross & b).sum()
    fpr = (flagged & ~gross & b).sum() / (~gross & b).sum()
    keep = b & ~flagged
    w05 = np.mean(np.abs(est[keep] - S[keep]) <= 0.5)
    roc.append({"thr_D": thr, "catches_gross_%": round(100 * tpr, 1), "false_flag_%": round(100 * fpr, 1),
                "kept_within_0.5D_%": round(100 * w05, 1)})

# add simulation
M = 40000
age = rng.uniform(45, 65, M)
A = np.clip(18.5 - 0.30 * age + rng.normal(0, 1.0, M), 0.5, None)
WD = 0.40
add_true = np.clip(1 / WD - A / 2, 0.75, 3.0)
add_res = {}
for card, sr in [("card", SIG_REL_CARD), ("nocard", SIG_REL_NOCARD)]:
    ests = []
    for _ in range(2):
        Vnp = A + rng.normal(0, SIG_JUDGE, M) + rng.normal(0, SIG_BIAS, M) * 0.5
        d = (1 / np.maximum(Vnp, 0.3)) * (1 + rng.normal(0, sr, M))
        ests.append(1 / d)
    A_m = np.mean(ests, 0)
    add_est = np.clip(np.round((1 / WD - A_m / 2) * 4) / 4, 0.75, 3.0)
    e = add_est - add_true
    add_res[card] = {"within_0.25D_%": round(100 * np.mean(np.abs(e) <= 0.25 + 1e-9), 1),
                     "within_0.5D_%": round(100 * np.mean(np.abs(e) <= 0.5 + 1e-9), 1),
                     "MAE_D": round(np.mean(np.abs(e)), 2)}

print(json.dumps({"far_point": res, "consistency_roc": roc, "add": add_res}, indent=1))
