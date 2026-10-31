#!/usr/bin/env python3
"""
Paso 3 y 4 del Pipeline de Visión (Punto de Control G4):
Entrena un detector ligero YOLOv8n con letterboxing (compatible con pantallas reales 20:9, 19.5:9 y 16:9),
evalúa el mAP@50 sobre el conjunto de validación no usado en entrenamiento (umbral obligatorio >= 85%),
y si pasa el punto de control G4, exporta `oxide_detector.tflite` listo para copiar a `app/src/main/assets/`.
"""

import argparse
import shutil
import sys
from pathlib import Path
from ultralytics import YOLO


def train_validate_and_export(data_yaml: Path, epochs: int, imgsz: int, output_asset: Path) -> None:
    model = YOLO("yolov8n.pt")

    print(f"[1/3] Entrenando detector de Oxide ({epochs} épocas, imgsz={imgsz} con Letterbox)...")
    model.train(
        data=str(data_yaml),
        epochs=epochs,
        imgsz=imgsz,
        rect=False,  # Usa Letterbox cuadrado 320x320 igual que OxideVisionPipeline.kt en Android
        degrees=0.0,
        fliplr=0.5,
        mosaic=0.8
    )

    print("[2/3] Evaluando sobre capturas NO vistas en el entrenamiento (Punto de Control G4)...")
    metrics = model.val(data=str(data_yaml), split="val", imgsz=imgsz)
    map50 = float(metrics.box.map50)
    print(f"Resultado mAP@50 en validación: {map50 * 100.0:.2f}% (Umbral mínimo G4: 85.00%)")

    if map50 < 0.85:
        print("===================================================================")
        print("[CRITERIO DE ABANDONO / REVISIÓN G4]: El modelo obtuvo menos de 85%.")
        print("No se exporta a producción hasta alcanzar >= 85% en validación no vista.")
        print("===================================================================")
        sys.exit(2)

    print("[3/3] Exportando modelo a TensorFlow Lite (float16 / int8)...")
    exported_path = Path(model.export(format="tflite", imgsz=imgsz, half=True))
    output_asset.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(exported_path, output_asset)
    print(f"[OK] Modelo exportado exitosamente a: {output_asset}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Entrena, valida G4 (>=85%) y exporta oxide_detector.tflite")
    parser.add_argument("--data", type=Path, default=Path("./dataset_config.yaml"))
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--imgsz", type=int, default=320)
    parser.add_argument(
        "--output_asset",
        type=Path,
        default=Path("../app/src/main/assets/oxide_detector.tflite")
    )
    args = parser.parse_args()
    train_validate_and_export(args.data, args.epochs, args.imgsz, args.output_asset)
