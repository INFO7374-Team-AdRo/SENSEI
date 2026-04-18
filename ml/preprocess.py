"""
Data preprocessing for MultimodalGasData dataset.
Cleans, normalizes, and prepares data for XGBoost training.
"""
import pandas as pd
import numpy as np
import json
import os

DATA_DIR = os.path.join(os.path.dirname(__file__), 'data')

def load_and_clean_data(csv_path=None):
    """Load the dataset and clean it."""
    if csv_path is None:
        csv_path = os.path.join(DATA_DIR, 'expanded_final_dataset.csv')

    print(f"Loading data from: {csv_path}")
    df = pd.read_csv(csv_path)

    # Strip whitespace from column names
    df.columns = df.columns.str.strip()

    print(f"Dataset shape: {df.shape}")
    print(f"Columns: {list(df.columns)}")
    print(f"\nLabel distribution:")
    print(df['label'].value_counts() if 'label' in df.columns else "No 'label' column found")

    return df


def identify_sensor_columns(df):
    """Identify which columns are sensor readings."""
    sensor_cols = []
    possible_sensors = ['MQ2', 'MQ3', 'MQ4', 'MQ5', 'MQ6', 'MQ7', 'MQ8', 'MQ135',
                        'mq2', 'mq3', 'mq4', 'mq5', 'mq6', 'mq7', 'mq8', 'mq135']

    for col in df.columns:
        col_clean = col.strip().upper()
        if col_clean in [s.upper() for s in possible_sensors]:
            sensor_cols.append(col)

    # Also check for numeric columns that might be sensors
    if not sensor_cols:
        numeric_cols = df.select_dtypes(include=[np.number]).columns.tolist()
        print(f"Numeric columns found: {numeric_cols}")
        sensor_cols = numeric_cols

    print(f"Sensor columns identified: {sensor_cols}")
    return sensor_cols


def encode_labels(df, label_col='label'):
    """Encode string labels to integers."""
    label_mapping = {
        'No Gas': 0, 'no gas': 0, 'NO_GAS': 0, 'nogas': 0,
        'Smoke': 1, 'smoke': 1, 'SMOKE': 1,
        'Perfume': 2, 'perfume': 2, 'PERFUME': 2,
        'Combined': 3, 'combined': 3, 'COMBINED': 3,
        'Mixture': 3, 'mixture': 3, 'MIXTURE': 3,
    }

    if label_col in df.columns:
        df['label_encoded'] = df[label_col].map(label_mapping)
        # Handle unmapped labels
        unmapped = df[df['label_encoded'].isna()][label_col].unique()
        if len(unmapped) > 0:
            print(f"WARNING: Unmapped labels found: {unmapped}")
            # Assign sequential integers for unmapped
            for i, label in enumerate(unmapped):
                df.loc[df[label_col] == label, 'label_encoded'] = 4 + i

        df['label_encoded'] = df['label_encoded'].astype(int)
    else:
        print(f"Label column '{label_col}' not found. Available: {list(df.columns)}")

    return df


def compute_normalization(df, sensor_cols):
    """Compute and save normalization parameters."""
    means = df[sensor_cols].mean().values.tolist()
    stds = df[sensor_cols].std().values.tolist()
    # Replace zero stds with 1 to avoid division by zero
    stds = [s if s > 0 else 1.0 for s in stds]

    config = {
        'feature_names': sensor_cols,
        'means': means,
        'stds': stds,
        'n_features': len(sensor_cols)
    }

    config_path = os.path.join(DATA_DIR, 'feature_config.json')
    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)
    print(f"Feature config saved to: {config_path}")

    # Also copy to resources directory for Java
    resources_dir = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
    if os.path.exists(resources_dir):
        resources_config = os.path.join(resources_dir, 'feature_config.json')
        with open(resources_config, 'w') as f:
            json.dump(config, f, indent=2)
        print(f"Feature config also saved to: {resources_config}")

    return config


def preprocess_pipeline(csv_path=None):
    """Full preprocessing pipeline."""
    df = load_and_clean_data(csv_path)
    sensor_cols = identify_sensor_columns(df)
    df = encode_labels(df)
    config = compute_normalization(df, sensor_cols)

    print(f"\nPreprocessing complete:")
    print(f"  Samples: {len(df)}")
    print(f"  Features: {len(sensor_cols)}")
    print(f"  Classes: {df['label_encoded'].nunique() if 'label_encoded' in df.columns else 'N/A'}")

    return df, sensor_cols, config


if __name__ == '__main__':
    df, sensor_cols, config = preprocess_pipeline()
    print("\nFirst 5 rows:")
    print(df.head())
    print(f"\nSensor statistics:")
    print(df[sensor_cols].describe())
