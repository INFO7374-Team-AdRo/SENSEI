"""
Evaluation script - generates confusion matrix and comparison plots.
"""
import os
import json
import numpy as np
import matplotlib
matplotlib.use('Agg')  # Non-interactive backend
import matplotlib.pyplot as plt
import seaborn as sns

DATA_DIR = os.path.join(os.path.dirname(__file__), 'data')


def plot_results():
    """Generate evaluation plots from training results."""
    results_path = os.path.join(DATA_DIR, 'training_results.json')

    if not os.path.exists(results_path):
        print("No training results found. Run train_classifier.py first.")
        return

    with open(results_path, 'r') as f:
        results = json.load(f)

    # Accuracy comparison bar chart
    fig, ax = plt.subplots(figsize=(10, 6))

    sensors = list(results['single_modality'].keys())
    single_accs = [results['single_modality'][s] for s in sensors]
    fused_acc = results['fused_accuracy']

    labels = sensors + ['FUSED']
    values = single_accs + [fused_acc]
    colors = ['#94a3b8'] * len(sensors) + ['#22c55e']

    bars = ax.bar(labels, values, color=colors, edgecolor='white', linewidth=0.5)
    ax.set_ylabel('Accuracy', fontsize=12)
    ax.set_title('Single-Modality vs. Fused Classification Accuracy', fontsize=14)
    ax.set_ylim(0, 1.0)

    for bar, val in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.01,
                f'{val:.3f}', ha='center', fontsize=9)

    plt.xticks(rotation=45, ha='right')
    plt.tight_layout()

    plot_path = os.path.join(DATA_DIR, 'accuracy_comparison.png')
    plt.savefig(plot_path, dpi=150)
    print(f"Accuracy comparison plot saved to: {plot_path}")
    plt.close()

    print(f"\n=== Results Summary ===")
    print(f"Fused accuracy: {fused_acc:.4f}")
    print(f"Best single sensor: {max(results['single_modality'], key=results['single_modality'].get)} "
          f"({max(single_accs):.4f})")
    print(f"Worst single sensor: {min(results['single_modality'], key=results['single_modality'].get)} "
          f"({min(single_accs):.4f})")
    improvement = ((fused_acc - max(single_accs)) / max(single_accs)) * 100
    print(f"Fused improvement over best single: {improvement:+.1f}%")


if __name__ == '__main__':
    plot_results()
