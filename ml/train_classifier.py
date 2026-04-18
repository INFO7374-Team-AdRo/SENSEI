"""
Train XGBoost classifier on MultimodalGasData and export to ONNX.
Classifies: No Gas (0), Smoke (1), Perfume (2), Combined (3)
"""
import os
import json
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score
import xgboost as xgb

from preprocess import preprocess_pipeline

DATA_DIR = os.path.join(os.path.dirname(__file__), 'data')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')


def train_xgboost(X_train, y_train, X_test, y_test, n_classes=4):
    """Train XGBoost multiclass classifier."""
    print("\n=== Training XGBoost Classifier ===")

    model = xgb.XGBClassifier(
        n_estimators=100,
        max_depth=6,
        learning_rate=0.1,
        objective='multi:softprob',
        num_class=n_classes,
        eval_metric='mlogloss',
        use_label_encoder=False,
        random_state=42,
        n_jobs=-1
    )

    model.fit(
        X_train, y_train,
        eval_set=[(X_test, y_test)],
        verbose=10
    )

    # Evaluate
    y_pred = model.predict(X_test)
    y_proba = model.predict_proba(X_test)

    label_names = ['No Gas', 'Smoke', 'Perfume', 'Combined']
    print("\n=== Classification Report ===")
    print(classification_report(y_test, y_pred, target_names=label_names[:n_classes]))

    print("=== Confusion Matrix ===")
    print(confusion_matrix(y_test, y_pred))

    accuracy = accuracy_score(y_test, y_pred)
    print(f"\nTest Accuracy: {accuracy:.4f}")

    return model, accuracy


def export_to_onnx(model, n_features, output_path):
    """Export XGBoost model to ONNX format."""
    print(f"\n=== Exporting model to ONNX ===")

    try:
        from onnxmltools import convert_xgboost
        from onnxmltools.convert.common.data_types import FloatTensorType

        initial_type = [('features', FloatTensorType([None, n_features]))]
        onnx_model = convert_xgboost(model, initial_types=initial_type)

        with open(output_path, 'wb') as f:
            f.write(onnx_model.SerializeToString())

        print(f"ONNX model saved to: {output_path}")
        print(f"Model size: {os.path.getsize(output_path) / 1024:.1f} KB")

    except ImportError:
        print("onnxmltools not available, trying alternative export...")
        try:
            # Alternative: use skl2onnx with XGBoost wrapper
            from skl2onnx import convert_sklearn
            from skl2onnx.common.data_types import FloatTensorType as FloatType

            initial_type = [('features', FloatType([None, n_features]))]
            onnx_model = convert_sklearn(model, initial_types=initial_type)

            with open(output_path, 'wb') as f:
                f.write(onnx_model.SerializeToString())
            print(f"ONNX model saved via skl2onnx to: {output_path}")
        except Exception as e2:
            print(f"ONNX export failed: {e2}")
            # Fallback: save as XGBoost native format
            native_path = output_path.replace('.onnx', '.json')
            model.save_model(native_path)
            print(f"Saved as XGBoost native format: {native_path}")


def train_single_modality(X_train, y_train, X_test, y_test, sensor_cols, n_classes):
    """Train single-modality classifiers for comparison."""
    print("\n=== Single-Modality Comparison ===")
    results = {}

    for col_idx, col_name in enumerate(sensor_cols):
        X_train_single = X_train[:, col_idx:col_idx+1]
        X_test_single = X_test[:, col_idx:col_idx+1]

        model = xgb.XGBClassifier(
            n_estimators=50, max_depth=4,
            objective='multi:softprob', num_class=n_classes,
            use_label_encoder=False, verbosity=0, random_state=42
        )
        model.fit(X_train_single, y_train)
        y_pred = model.predict(X_test_single)
        acc = accuracy_score(y_test, y_pred)
        results[col_name] = acc
        print(f"  {col_name}: accuracy = {acc:.4f}")

    return results


def main():
    # Preprocess data
    df, sensor_cols, config = preprocess_pipeline()

    if 'label_encoded' not in df.columns:
        print("ERROR: No encoded labels found. Check dataset format.")
        return

    # Prepare features and labels
    X = df[sensor_cols].values.astype(np.float32)
    y = df['label_encoded'].values.astype(int)

    n_classes = len(np.unique(y))
    print(f"\nClasses: {n_classes}, Features: {X.shape[1]}, Samples: {X.shape[0]}")

    # Train/test split
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )
    print(f"Train: {X_train.shape[0]}, Test: {X_test.shape[0]}")

    # Train fused (multimodal) classifier
    model, fused_accuracy = train_xgboost(X_train, y_train, X_test, y_test, n_classes)

    # Single-modality comparison
    single_results = train_single_modality(X_train, y_train, X_test, y_test, sensor_cols, n_classes)

    # Print comparison
    print("\n=== Accuracy Comparison ===")
    print(f"  Fused (all sensors): {fused_accuracy:.4f}")
    for sensor, acc in single_results.items():
        improvement = ((fused_accuracy - acc) / acc) * 100
        print(f"  {sensor} alone: {acc:.4f} (fused is {improvement:+.1f}% better)")

    # Export to ONNX
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    onnx_path = os.path.join(OUTPUT_DIR, 'gas_classifier.onnx')
    export_to_onnx(model, X.shape[1], onnx_path)

    # Also save to ml/data for reference
    data_onnx = os.path.join(DATA_DIR, 'gas_classifier.onnx')
    export_to_onnx(model, X.shape[1], data_onnx)

    # Save training results
    results = {
        'fused_accuracy': float(fused_accuracy),
        'single_modality': {k: float(v) for k, v in single_results.items()},
        'n_classes': n_classes,
        'n_features': X.shape[1],
        'n_train': int(X_train.shape[0]),
        'n_test': int(X_test.shape[0]),
        'sensor_columns': sensor_cols
    }

    results_path = os.path.join(DATA_DIR, 'training_results.json')
    with open(results_path, 'w') as f:
        json.dump(results, f, indent=2)
    print(f"\nTraining results saved to: {results_path}")


if __name__ == '__main__':
    main()
