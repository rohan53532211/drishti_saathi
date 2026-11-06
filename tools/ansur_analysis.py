import json
import sys
import pandas as pd

src = sys.argv[1] if len(sys.argv) > 1 else "ansur2.csv"
dst = sys.argv[2] if len(sys.argv) > 2 else "anthro_priors.json"

df = pd.read_csv(src)
cols = {c.lower(): c for c in df.columns}
pd_col, fw_col = cols["interpupillarybreadth"], cols["bizygomaticbreadth"]
sex_col = next((cols[k] for k in ("gender", "sex") if k in cols), None)

d = df[[pd_col, fw_col] + ([sex_col] if sex_col else [])].dropna().copy()
d.columns = ["pd", "fw"] + (["sex"] if sex_col else [])

# some ANSUR measures are stored in tenths of mm
for c in ("pd", "fw"):
    if d[c].median() > 300:
        d[c] = d[c] / 10

d["ratio"] = d["pd"] / d["fw"]


def stats(x):
    return {"mean": round(x.mean(), 2), "sd": round(x.std(), 2),
            "p1": round(x.quantile(0.01), 3), "p99": round(x.quantile(0.99), 3)}


out = {"source": "ANSUR II", "n": int(len(d)), "pd_mm": stats(d["pd"]),
       "face_width_mm": stats(d["fw"]), "pd_to_face_ratio": stats(d["ratio"])}

slope = d["pd"].cov(d["fw"]) / d["fw"].var()
intercept = d["pd"].mean() - slope * d["fw"].mean()
resid = d["pd"] - (slope * d["fw"] + intercept)
out["pd_from_face_width"] = {"slope": round(slope, 4), "intercept": round(intercept, 2),
                             "resid_sd_mm": round(resid.std(), 2),
                             "corr": round(d["pd"].corr(d["fw"]), 3)}

if "sex" in d:
    out["by_sex"] = {str(s): {"n": int(len(g)), "pd_mean": round(g["pd"].mean(), 2),
                              "fw_mean": round(g["fw"].mean(), 2)}
                     for s, g in d.groupby("sex")}

print(json.dumps(out, indent=2))
with open(dst, "w") as f:
    json.dump(out, f, indent=2)
