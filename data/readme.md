data/ Dataset Notes
This folder contains the gas-sensor and thermal-image dataset used by the backend replay pipeline.

Source
The data in this directory comes from the Mendeley Data dataset:

Dataset: MultimodalGasData: Multimodal Dataset for Gas Detection and Classification
Version: 2
DOI: 10.17632/zkwgkjkjn9.2
URL: https://data.mendeley.com/datasets/zkwgkjkjn9/2
License: CC BY 4.0
According to the dataset page, this dataset provides simultaneous measurements from:

seven MQ-series gas sensors: MQ2, MQ3, MQ5, MQ6, MQ7, MQ8, MQ135
a thermal camera
four classes: No Gas, Perfume, Smoke, and Mixture
What Is Used In This Project
This project uses the following assets from that dataset:

Gas_Sensors_Measurements.csv
Thermal Camera Images
The thermal image folder currently contains class-based subfolders such as:

Mixture
NoGas
Perfume
Smoke
Sample Thermal Camera Images
How The Code Uses It
The backend expects:

CSV replay file path: data/Gas_Sensors_Measurements.csv
thermal image base path: data/Thermal Camera Images
Relevant code references:

application.conf (line 58)
DataReplayStream.java (line 32)
SafetyHttpServer.java (line 456)
At runtime:

DataReplayStream replays Gas_Sensors_Measurements.csv
the MQ sensor values are sent into the Akka sensor/fusion/classification pipeline
thermal images are served/analyzed from data/Thermal Camera Images
the gas classifier maps replayed readings into the project’s hazard classes shown in the dashboard
Local Folder Intent
This folder is treated as a checked-in local runtime asset directory for demos and development. If you replace the dataset contents, keep the same relative paths unless you also update configuration and code references.

Attribution
Please retain attribution to the original Mendeley dataset and its contributors when redistributing or publishing results derived from this folder, in line with the dataset’s CC BY 4.0 license.
