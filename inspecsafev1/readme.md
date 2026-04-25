# `inspecsafev1/` Dataset Notes

This folder contains the local InspecSafe V1 visual-inspection dataset used by the `VisualInspectionAgent`.

## Source

The local dataset content was taken from the Hugging Face dataset:

- Dataset: `Tetrabot2026/InspecSafe-V1`
- URL: [https://huggingface.co/datasets/Tetrabot2026/InspecSafe-V1](https://huggingface.co/datasets/Tetrabot2026/InspecSafe-V1)
- License shown on the dataset card: `cc-by-4.0`

For this project, the local folder is based on the `test` split from that dataset.

## What Is In This Folder

Top-level structure in this repo:

- [Annotations]
- [Other_modalities]
- [Parameters]

## Project Structure

Expected local structure:

```text
industrial-safety-agent/
`-- inspecsafev1/
    |-- Annotations/
    |   |-- Anomaly_data/
    |   `-- Normal_data/
    |-- Other_modalities/
    |   `-- <waypoint-id>/
    |       |-- *_audio_*.wav
    |       |-- *_infrared_*.mp4
    |       `-- *_sensor_*.txt
    `-- Parameters/
        |-- Depth_Camera_params.json
        |-- IR_Camera_params.json
        |-- LiDAR_params.json
        `-- RGB_Camera_params.json
```

Important subfolders:

- `Annotations/Anomaly_data`
- `Annotations/Normal_data`
- `Other_modalities/<waypoint-id>/...`
- `Parameters/*.json`

Examples of parameter files present locally:

- `Depth_Camera_params.json`
- `IR_Camera_params.json`
- `LiDAR_params.json`
- `RGB_Camera_params.json`


At runtime, `VisualInspectionAgent`:

- scans `Annotations/Normal_data` and `Annotations/Anomaly_data`
- loads JPG frames and human-readable text descriptions from waypoint folders
- derives anomaly type and environment from folder names and annotation text
- looks up matching files in `Other_modalities/<waypoint-id>/`
- attaches audio, infrared video, and sensor text or JSON files when available
- emits `InspectionEvent` objects to the HTTP/dashboard pipeline

The backend is intentionally written to support either:

- split-based layout such as `test/Annotations/...`
- or a flattened local root like this repo's `inspecsafev1/Annotations/...`

That is why this folder works directly as `INSPECSAFE_DATA_PATH` without keeping the original Hugging Face split directory.

## Where To Put Files

Put dataset files under this local root:

- [inspecsafev1](/C:/Users/Rohan/OneDrive/Desktop/Material%20SES/Assignments/Agent%20Infra/industrial-safety-agent/inspecsafev1)

Put files in these exact locations:

- image and description waypoint folders:
  `inspecsafev1/Annotations/Anomaly_data/<waypoint-id>/`
  `inspecsafev1/Annotations/Normal_data/<waypoint-id>/`
- sidecar modality files:
  `inspecsafev1/Other_modalities/<waypoint-id>/`
- camera and sensor parameter files:
  `inspecsafev1/Parameters/`

Inside each waypoint annotation folder, the code expects:

- one `.jpg` image
- one descriptive `.txt` annotation
- optionally a local `*_sensor_*.txt` file

Inside each `Other_modalities/<waypoint-id>/` folder, the code can resolve:

- `*_audio_*.wav`
- `*_infrared_*.mp4`
- `*_sensor_*.txt`

Quick reference:

```text
Put waypoint image + text folders here:
inspecsafev1/Annotations/Anomaly_data/<waypoint-id>/
inspecsafev1/Annotations/Normal_data/<waypoint-id>/

Put audio / infrared / sensor sidecar files here:
inspecsafev1/Other_modalities/<waypoint-id>/

Put parameter JSON files here:
inspecsafev1/Parameters/
```

## Notes About This Local Copy

- This repo uses the `test` subset as a local runtime dataset for demo and dashboard playback.
- The local folder is an extracted working copy, not a verbatim Hugging Face repository mirror.
- Folder names and waypoint IDs are used by the code to resolve paired media and sensor files, so they should be preserved.

## Attribution

If you share this project or derived outputs, keep attribution to the Hugging Face dataset source and respect the `CC BY 4.0` license shown on the dataset card.
