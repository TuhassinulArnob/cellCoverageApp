#!/usr/bin/env python3
"""
CellScope Anomaly Detection: Machine Learning Training & Evaluation Pipeline
Evaluates an unsupervised Isolation Forest model on multi-dimensional cellular drive-test telemetry
and compares it with deterministic rule-based baselines.
"""

import json
import math
import os
import numpy as np
from sklearn.ensemble import IsolationForest
from sklearn.metrics import precision_recall_fscore_support, roc_auc_score, confusion_matrix

def generate_synthetic_drive_data(n_samples=5000, random_seed=42):
    np.random.seed(random_seed)
    
    # 1. Normal Urban Driving (45%)
    n_urban = int(n_samples * 0.45)
    urban_rsrp = np.random.normal(-88, 7, n_urban).clip(-120, -55)
    urban_rsrq = np.random.normal(-10, 2.5, n_urban).clip(-18, -4)
    urban_sinr = np.random.normal(14, 5, n_urban).clip(-5, 28)
    urban_speed = np.random.normal(32, 12, n_urban).clip(0, 75)
    urban_d_rsrp = np.random.normal(0, 2.0, n_urban).clip(-8, 8)
    urban_dt = np.random.normal(1.0, 0.08, n_urban).clip(0.8, 1.4)
    y_urban = np.zeros(n_urban, dtype=int)
    
    # 2. Normal Highway Driving (35%)
    n_highway = int(n_samples * 0.35)
    highway_rsrp = np.random.normal(-94, 9, n_highway).clip(-125, -60)
    highway_rsrq = np.random.normal(-11, 3, n_highway).clip(-19, -5)
    highway_sinr = np.random.normal(12, 6, n_highway).clip(-8, 26)
    highway_speed = np.random.normal(88, 15, n_highway).clip(50, 135)
    highway_d_rsrp = np.random.normal(0, 3.5, n_highway).clip(-10, 10)
    highway_dt = np.random.normal(1.0, 0.08, n_highway).clip(0.8, 1.4)
    y_highway = np.zeros(n_highway, dtype=int)
    
    # 3. Normal Stationary / Fringe (10%)
    n_stationary = int(n_samples * 0.10)
    stat_rsrp = np.random.normal(-106, 4, n_stationary).clip(-122, -92)
    stat_rsrq = np.random.normal(-13, 2, n_stationary).clip(-18, -7)
    stat_sinr = np.random.normal(4, 3, n_stationary).clip(-6, 15)
    stat_speed = np.random.normal(0.5, 0.5, n_stationary).clip(0, 3)
    stat_d_rsrp = np.random.normal(0, 0.8, n_stationary).clip(-3, 3)
    stat_dt = np.random.normal(1.0, 0.08, n_stationary).clip(0.8, 1.4)
    y_stat = np.zeros(n_stationary, dtype=int)
    
    # 4. Injected Anomalies (10%): Abrupt drops, weak corridors, pilot pollution, telemetry gaps
    n_anomalies = n_samples - (n_urban + n_highway + n_stationary)
    
    # Sub-type A: Abrupt signal drops (overpass/obstacle shadow fading)
    n_a = n_anomalies // 4
    a_rsrp = np.random.normal(-108, 6, n_a).clip(-130, -96)
    a_rsrq = np.random.normal(-15, 3, n_a).clip(-20, -8)
    a_sinr = np.random.normal(-2, 4, n_a).clip(-15, 6)
    a_speed = np.random.normal(45, 15, n_a).clip(15, 90)
    a_d_rsrp = np.random.normal(-24, 3, n_a).clip(-38, -19) # Huge drop
    a_dt = np.random.normal(1.0, 0.1, n_a).clip(0.8, 1.5)
    
    # Sub-type B: Sustained weak coverage corridor / dead zone
    n_b = n_anomalies // 4
    b_rsrp = np.random.normal(-122, 4, n_b).clip(-135, -116) # Very weak
    b_rsrq = np.random.normal(-17, 2, n_b).clip(-21, -12)
    b_sinr = np.random.normal(-8, 3, n_b).clip(-18, -2)
    b_speed = np.random.normal(55, 12, n_b).clip(20, 95)
    b_d_rsrp = np.random.normal(0, 2, n_b).clip(-5, 5)
    b_dt = np.random.normal(1.0, 0.1, n_b).clip(0.8, 1.5)
    
    # Sub-type C: Pilot pollution / excessive noise (good RSRP, disastrous SINR & RSRQ)
    n_c = n_anomalies // 4
    c_rsrp = np.random.normal(-86, 5, n_c).clip(-98, -75) # Decent RSRP
    c_rsrq = np.random.normal(-18, 1.5, n_c).clip(-22, -15) # Severely degraded
    c_sinr = np.random.normal(-7, 2, n_c).clip(-14, -3) # Negative SINR
    c_speed = np.random.normal(30, 10, n_c).clip(5, 60)
    c_d_rsrp = np.random.normal(0, 2, n_c).clip(-5, 5)
    c_dt = np.random.normal(1.0, 0.1, n_c).clip(0.8, 1.5)
    
    # Sub-type D: Telemetry gap / OS throttle / GPS drop
    n_d = n_anomalies - (n_a + n_b + n_c)
    d_rsrp = np.random.normal(-90, 8, n_d).clip(-115, -70)
    d_rsrq = np.random.normal(-11, 2, n_d).clip(-17, -6)
    d_sinr = np.random.normal(10, 4, n_d).clip(-2, 20)
    d_speed = np.random.normal(40, 15, n_d).clip(5, 80)
    d_d_rsrp = np.random.normal(0, 3, n_d).clip(-8, 8)
    d_dt = np.random.normal(14.0, 4.0, n_d).clip(9.5, 30.0) # Gap > 10s
    
    y_anomalies = np.ones(n_anomalies, dtype=int)
    
    # Combine datasets
    rsrp = np.concatenate([urban_rsrp, highway_rsrp, stat_rsrp, a_rsrp, b_rsrp, c_rsrp, d_rsrp])
    rsrq = np.concatenate([urban_rsrq, highway_rsrq, stat_rsrq, a_rsrq, b_rsrq, c_rsrq, d_rsrq])
    sinr = np.concatenate([urban_sinr, highway_sinr, stat_sinr, a_sinr, b_sinr, c_sinr, d_sinr])
    speed = np.concatenate([urban_speed, highway_speed, stat_speed, a_speed, b_speed, c_speed, d_speed])
    d_rsrp = np.concatenate([urban_d_rsrp, highway_d_rsrp, stat_d_rsrp, a_d_rsrp, b_d_rsrp, c_d_rsrp, d_d_rsrp])
    dt = np.concatenate([urban_dt, highway_dt, stat_dt, a_dt, b_dt, c_dt, d_dt])
    y = np.concatenate([y_urban, y_highway, y_stat, y_anomalies])
    
    X = np.column_stack([rsrp, rsrq, sinr, speed, d_rsrp, dt])
    return X, y

def evaluate_rule_based_baseline(X):
    # Rule definitions:
    # 1. Abrupt drop: delta_rsrp <= -18
    # 2. Sustained weak: rsrp <= -116 and speed >= 4
    # 3. Pilot pollution: sinr <= -4 and rsrq <= -15 and rsrp >= -95
    # 4. Telemetry gap: dt >= 9.0
    is_anomaly = (
        (X[:, 4] <= -18.0) |
        ((X[:, 0] <= -116.0) & (X[:, 3] >= 4.0)) |
        ((X[:, 2] <= -4.0) & (X[:, 1] <= -15.0) & (X[:, 0] >= -95.0)) |
        (X[:, 5] >= 9.0)
    )
    return is_anomaly.astype(int)

def export_isolation_forest_json(clf, feature_names, output_path):
    trees_data = []
    for est in clf.estimators_:
        tree = est.tree_
        children_left = tree.children_left.tolist()
        children_right = tree.children_right.tolist()
        feature = tree.feature.tolist()
        threshold = [round(float(t), 4) if t != -2.0 else -2.0 for t in tree.threshold.tolist()]
        n_node_samples = tree.n_node_samples.tolist()
        
        trees_data.append({
            "children_left": children_left,
            "children_right": children_right,
            "feature": feature,
            "threshold": threshold,
            "n_node_samples": n_node_samples
        })
        
    model_payload = {
        "model_type": "IsolationForest",
        "n_features": len(feature_names),
        "feature_names": feature_names,
        "n_estimators": len(trees_data),
        "max_samples": clf.max_samples_,
        "offset": float(clf.offset_),
        "trees": trees_data
    }
    
    with open(output_path, "w") as f:
        json.dump(model_payload, f, indent=2)
    print(f"Exported Isolation Forest model JSON to {output_path}")

def main():
    print("Generating synthetic cellular drive-test dataset (5000 samples)...")
    X, y_true = generate_synthetic_drive_data(n_samples=5000, random_seed=42)
    feature_names = ["rsrpDbm", "rsrqDb", "sinrDb", "speedKmh", "deltaRsrp", "sampleDt"]
    
    # Train Isolation Forest
    n_estimators = 25
    contamination = 0.10
    clf = IsolationForest(
        n_estimators=n_estimators,
        max_samples=256,
        contamination=contamination,
        random_state=42
    )
    print("Fitting Isolation Forest model...")
    clf.fit(X)
    
    # Scores: decision_function returns negative for outliers, positive for inliers
    # Isolation Forest anomaly score convention: higher score = more anomalous
    raw_scores = -clf.decision_function(X) # Higher = more anomalous
    y_pred_ml = (clf.predict(X) == -1).astype(int)
    
    # Evaluate ML
    prec_ml, rec_ml, f1_ml, _ = precision_recall_fscore_support(y_true, y_pred_ml, average='binary')
    roc_auc_ml = roc_auc_score(y_true, raw_scores)
    cm_ml = confusion_matrix(y_true, y_pred_ml).tolist()
    
    # Evaluate Rule-based
    y_pred_rules = evaluate_rule_based_baseline(X)
    prec_r, rec_r, f1_r, _ = precision_recall_fscore_support(y_true, y_pred_rules, average='binary')
    roc_auc_r = roc_auc_score(y_true, y_pred_rules)
    cm_r = confusion_matrix(y_true, y_pred_rules).tolist()
    
    # Agreement between rules and ML
    agreement_count = np.sum(y_pred_ml == y_pred_rules)
    agreement_pct = (agreement_count / len(y_true)) * 100.0
    
    report = {
        "dataset": {
            "total_samples": len(y_true),
            "normal_samples": int(np.sum(y_true == 0)),
            "anomaly_samples": int(np.sum(y_true == 1)),
            "features": feature_names
        },
        "isolation_forest_metrics": {
            "precision": round(float(prec_ml), 4),
            "recall": round(float(rec_ml), 4),
            "f1_score": round(float(f1_ml), 4),
            "roc_auc": round(float(roc_auc_ml), 4),
            "confusion_matrix": cm_ml
        },
        "rule_based_metrics": {
            "precision": round(float(prec_r), 4),
            "recall": round(float(rec_r), 4),
            "f1_score": round(float(f1_r), 4),
            "roc_auc": round(float(roc_auc_r), 4),
            "confusion_matrix": cm_r
        },
        "ml_and_rule_agreement_pct": round(float(agreement_pct), 2),
        "false_positive_comparison": {
            "ml_false_positives": cm_ml[0][1],
            "rule_false_positives": cm_r[0][1]
        }
    }
    
    print("\n=== EVALUATION REPORT ===")
    print(f"Dataset: {len(y_true)} samples ({report['dataset']['normal_samples']} normal, {report['dataset']['anomaly_samples']} ground truth anomalies)")
    print(f"ML Isolation Forest: Precision={prec_ml:.4f}, Recall={rec_ml:.4f}, F1={f1_ml:.4f}, ROC-AUC={roc_auc_ml:.4f}")
    print(f"Rule-Based Baseline: Precision={prec_r:.4f}, Recall={rec_r:.4f}, F1={f1_r:.4f}, ROC-AUC={roc_auc_r:.4f}")
    print(f"Model & Rule Agreement Rate: {agreement_pct:.2f}%")
    print(f"False Positives: ML={cm_ml[0][1]}, Rules={cm_r[0][1]}")
    
    # Write report
    os.makedirs("scripts", exist_ok=True)
    report_path = "scripts/anomaly_model_evaluation.json"
    with open(report_path, "w") as f:
        json.dump(report, f, indent=2)
    print(f"Saved evaluation metrics to {report_path}")
    
    # Export model JSON to Android assets
    assets_dir = "android/app/src/main/assets"
    os.makedirs(assets_dir, exist_ok=True)
    export_isolation_forest_json(clf, feature_names, os.path.join(assets_dir, "isolation_forest_model.json"))

if __name__ == "__main__":
    main()
