#!/usr/bin/env python3
"""
Paso 1 y 2 del Pipeline de Visión (Punto de Control G4):
Extrae las capturas tomadas desde el modo 'Dataset / G1' de la app Android
(/sdcard/Android/data/com.aistudio.oxidecompanion.rxkmpz/files/dataset_captures/),
verifica que ninguna sea un frame en negro (G1) y divide los pares (imagen .png + etiquetas YOLO .txt)
en conjuntos de entrenamiento (80%) y validación no vista (20%) para evaluar el criterio G4 (>= 85%).
"""

import argparse
import os
import random
import shutil
from pathlib import Path
import cv2
import numpy as np


def is_black_frame(img_bgr: np.ndarray) -> bool:
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    mean_luma = float(np.mean(gray))
    std_luma = float(np.std(gray))
    return mean_luma < 2.5 and std_luma < 1.5


def prepare_dataset(raw_dir: Path, out_dir: Path, val_ratio: float = 0.20, seed: int = 42) -> None:
    random.seed(seed)
    images = sorted(list(raw_dir.glob("*.png")) + list(raw_dir.glob("*.jpg")))
    if not images:
        print(f"[ERROR] No se encontraron capturas en {raw_dir}")
        return

    valid_pairs = []
    skipped_black = 0
    skipped_unlabeled = 0

    for img_path in images:
        img = cv2.imread(str(img_path))
        if img is None or is_black_frame(img):
            skipped_black += 1
            continue
        label_path = img_path.with_suffix(".txt")
        if not label_path.exists():
            skipped_unlabeled += 1
            continue
        valid_pairs.append((img_path, label_path))

    random.shuffle(valid_pairs)
    val_count = max(1, int(len(valid_pairs) * val_ratio))
    val_split = valid_pairs[:val_count]
    train_split = valid_pairs[val_count:]

    for split_name, items in [("train", train_split), ("val", val_split)]:
        img_out = out_dir / "images" / split_name
        lbl_out = out_dir / "labels" / split_name
        img_out.mkdir(parents=True, exist_ok=True)
        lbl_out.mkdir(parents=True, exist_ok=True)
        for img_p, lbl_p in items:
            shutil.copy2(img_p, img_out / img_p.name)
            shutil.copy2(lbl_p, lbl_out / lbl_p.name)

    print("=== RESUMEN DE PREPARACIÓN DE DATASET (G1 / G4) ===")
    print(f"Total capturas analizadas : {len(images)}")
    print(f"Descartadas por frame negro: {skipped_black}")
    print(f"Pendientes de etiquetar    : {skipped_unlabeled}")
    print(f"Entrenamiento (train)      : {len(train_split)}")
    print(f"Validación no vista (val)  : {len(val_split)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Prepara y divide capturas de Oxide para entrenar TFLite.")
    parser.add_argument("--raw_dir", type=Path, required=True, help="Carpeta con capturas .png y etiquetas .txt")
    parser.add_argument("--out_dir", type=Path, default=Path("./dataset_oxide"), help="Carpeta de salida YOLO")
    args = parser.parse_args()
    prepare_dataset(args.raw_dir, args.out_dir)
