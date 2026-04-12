#!/usr/bin/env python3
"""
Batch Label Processor for Fresenius Inventario.

Processes photos of product labels to extract Part No. and barcodes,
then updates the Google Sheets database via Apps Script.

Usage:
    python3 process_labels.py /path/to/photos --script-url "https://script.google.com/..."
    python3 process_labels.py /path/to/photos --dry-run   # Preview without updating
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.request
import urllib.parse
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter
import pytesseract
from pyzbar.pyzbar import decode as pyzbar_decode


# --- Part No. extraction (mirrors Android PartNoExtractor logic) ---

PART_NO_PATTERNS = [
    # "Part No." followed by reference
    re.compile(r'(?:Part|Pari|Paft|Pant)\s*(?:No|Nc|N[oO0]|#)\s*[.:]?\s*([A-Z]?\d{5,8}\d?)', re.IGNORECASE),
    # F-prefixed: F40011904
    re.compile(r'\b(F\d{7,9})\b'),
    # M-prefixed: M465231
    re.compile(r'\b(M\d{5,8})\b'),
    # Pure numeric 7+ digits (not starting with 0403 which is GTIN)
    re.compile(r'(?<![0-9])(?!0403)(\d{7,8})(?![0-9])'),
]


def extract_part_no(text):
    """Extract Part No. from OCR text."""
    for pattern in PART_NO_PATTERNS:
        match = pattern.search(text)
        if match:
            return match.group(1).upper()
    return None


# --- GS1-128 barcode cleaning ---

def clean_gs1_barcode(raw_data):
    """Clean GS1-128 barcode data: strip ]C1 prefix, format AIs."""
    if not raw_data:
        return None

    data = raw_data.strip()

    # Strip AIM symbology identifier ]C1
    if data.startswith(']'):
        data = data[3:] if len(data) >= 3 else data[1:]

    # Remove GS characters (ASCII 29)
    data = data.replace('\x1d', '')

    # If it's digits only and long enough, try to format as GS1
    if data.isdigit() and len(data) >= 16:
        formatted = format_gs1(data)
        if formatted:
            return formatted

    # If already has parentheses, return as-is
    if '(' in data and ')' in data:
        return data

    return data


def format_gs1(data):
    """Parse raw GS1 digit string into (AI)value format."""
    ai_lengths = {
        '01': 14, '02': 14, '11': 6, '13': 6,
        '15': 6, '17': 6, '20': 2,
    }

    result = []
    pos = 0
    while pos < len(data):
        ai = data[pos:pos+2]
        if ai in ai_lengths:
            length = ai_lengths[ai]
            value = data[pos+2:pos+2+length]
            result.append(f'({ai}){value}')
            pos += 2 + length
        else:
            # Can't parse further
            if result:
                result.append(data[pos:])
            break
            break

    return ''.join(result) if result else None


# --- Image preprocessing ---

def preprocess_image(img_path):
    """Load and preprocess an image for better OCR/barcode detection."""
    img = cv2.imread(str(img_path))
    if img is None:
        return None, None

    # Original for barcode detection
    original = img.copy()

    # Grayscale for OCR
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # Enhance contrast
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(gray)

    return original, enhanced


def detect_barcodes(img_path):
    """Detect barcodes using pyzbar with multiple preprocessing attempts."""
    barcodes_found = []

    # Try with original image
    img = cv2.imread(str(img_path))
    if img is None:
        return []

    decoded = pyzbar_decode(img)
    for d in decoded:
        raw = d.data.decode('utf-8', errors='replace')
        barcodes_found.append({
            'raw': raw,
            'type': d.type,
            'clean': clean_gs1_barcode(raw)
        })

    if barcodes_found:
        return barcodes_found

    # Try grayscale
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    decoded = pyzbar_decode(gray)
    for d in decoded:
        raw = d.data.decode('utf-8', errors='replace')
        barcodes_found.append({
            'raw': raw,
            'type': d.type,
            'clean': clean_gs1_barcode(raw)
        })

    if barcodes_found:
        return barcodes_found

    # Try with threshold
    _, thresh = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    decoded = pyzbar_decode(thresh)
    for d in decoded:
        raw = d.data.decode('utf-8', errors='replace')
        barcodes_found.append({
            'raw': raw,
            'type': d.type,
            'clean': clean_gs1_barcode(raw)
        })

    if barcodes_found:
        return barcodes_found

    # Try with enhanced contrast using PIL
    pil_img = Image.open(str(img_path))
    enhancer = ImageEnhance.Contrast(pil_img)
    enhanced = enhancer.enhance(2.0)
    enhanced_cv = cv2.cvtColor(np.array(enhanced), cv2.COLOR_RGB2BGR)
    decoded = pyzbar_decode(enhanced_cv)
    for d in decoded:
        raw = d.data.decode('utf-8', errors='replace')
        barcodes_found.append({
            'raw': raw,
            'type': d.type,
            'clean': clean_gs1_barcode(raw)
        })

    # Try multiple scales
    if not barcodes_found:
        for scale in [1.5, 2.0, 0.5]:
            resized = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
            decoded = pyzbar_decode(resized)
            for d in decoded:
                raw = d.data.decode('utf-8', errors='replace')
                barcodes_found.append({
                    'raw': raw,
                    'type': d.type,
                    'clean': clean_gs1_barcode(raw)
                })
            if barcodes_found:
                break

    return barcodes_found


def ocr_image(img_path):
    """Run OCR on an image and extract Part No."""
    img = cv2.imread(str(img_path))
    if img is None:
        return None, ""

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # Try multiple OCR configs
    configs = [
        '--oem 3 --psm 6',   # Assume uniform block of text
        '--oem 3 --psm 4',   # Assume single column
        '--oem 3 --psm 11',  # Sparse text
    ]

    for config in configs:
        text = pytesseract.image_to_string(gray, config=config)
        part_no = extract_part_no(text)
        if part_no:
            return part_no, text

    # Try with enhanced image
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(gray)
    text = pytesseract.image_to_string(enhanced, config='--oem 3 --psm 6')
    part_no = extract_part_no(text)
    if part_no:
        return part_no, text

    # Try with sharpening
    pil_img = Image.open(str(img_path))
    sharpened = pil_img.filter(ImageFilter.SHARPEN)
    text = pytesseract.image_to_string(sharpened, config='--oem 3 --psm 6')
    part_no = extract_part_no(text)

    return part_no, text


# --- Apps Script communication ---

def call_script(script_url, params, max_retries=3):
    """Call the Apps Script web app."""
    query = urllib.parse.urlencode(params)
    url = f"{script_url}?{query}"

    for attempt in range(max_retries):
        try:
            req = urllib.request.Request(url)
            # Follow redirects (Apps Script redirects after deploy)
            response = urllib.request.urlopen(req, timeout=30)
            data = json.loads(response.read().decode('utf-8'))
            return data
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 307, 308):
                redirect_url = e.headers.get('Location')
                if redirect_url:
                    response = urllib.request.urlopen(redirect_url, timeout=30)
                    data = json.loads(response.read().decode('utf-8'))
                    return data
            if attempt < max_retries - 1:
                time.sleep(2)
            else:
                raise
        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(2)
            else:
                raise


def get_products(script_url):
    """Get all products from the spreadsheet."""
    result = call_script(script_url, {'action': 'getProducts'})
    if 'error' in result:
        raise RuntimeError(result['error'])
    return result.get('products', [])


def update_barcode(script_url, row, barcode):
    """Update a product's barcode in the spreadsheet."""
    result = call_script(script_url, {
        'action': 'updateBarcode',
        'row': row,
        'barcode': barcode
    })
    return result


# --- Fuzzy matching (mirrors Android logic) ---

def find_closest_match(detected, products):
    """Find the closest matching product for a detected Part No."""
    detected_upper = detected.upper()

    # 1. Exact match
    for p in products:
        if p['partNo'].upper() == detected_upper:
            return p

    # 2. Trailing "1" rule (M and numeric refs end in 1, F refs don't)
    if not detected_upper.startswith('F'):
        with1 = detected_upper + '1'
        for p in products:
            if p['partNo'].upper() == with1:
                return p

    # 3. Prefix match (detected is prefix of known)
    prefix_matches = [p for p in products
                      if p['partNo'].upper().startswith(detected_upper)
                      and len(p['partNo']) <= len(detected_upper) + 2]
    if len(prefix_matches) == 1:
        return prefix_matches[0]

    # 4. Suffix match (known is prefix of detected)
    suffix_matches = [p for p in products
                      if detected_upper.startswith(p['partNo'].upper())
                      and len(detected_upper) <= len(p['partNo']) + 2]
    if len(suffix_matches) == 1:
        return suffix_matches[0]

    # 5. Levenshtein distance <= 2
    best_match = None
    best_dist = 999

    for p in products:
        known = p['partNo'].upper()
        if abs(len(known) - len(detected_upper)) > 1:
            continue
        dist = levenshtein(detected_upper, known)
        if 1 <= dist <= 2 and dist < best_dist:
            best_dist = dist
            best_match = p

    return best_match


def levenshtein(a, b):
    """Compute Levenshtein distance between two strings."""
    if len(a) < len(b):
        return levenshtein(b, a)
    if len(b) == 0:
        return len(a)

    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a):
        curr = [i + 1]
        for j, cb in enumerate(b):
            cost = 0 if ca == cb else 1
            curr.append(min(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost))
        prev = curr

    return prev[-1]


# --- Main processing ---

def process_folder(folder_path, script_url=None, dry_run=False):
    """Process all label images in a folder."""
    folder = Path(folder_path)
    image_extensions = {'.jpg', '.jpeg', '.png', '.bmp', '.tiff', '.tif', '.webp'}

    images = sorted([
        f for f in folder.iterdir()
        if f.suffix.lower() in image_extensions
    ])

    if not images:
        print(f"No images found in {folder}")
        return

    print(f"Found {len(images)} images to process")
    print("=" * 70)

    # Load products from spreadsheet
    products = []
    if script_url and not dry_run:
        print("Loading products from Google Sheets...")
        products = get_products(script_url)
        print(f"Loaded {len(products)} products")
        print("=" * 70)

    results = []
    success = 0
    partial = 0
    failed = 0

    for i, img_path in enumerate(images, 1):
        print(f"\n[{i}/{len(images)}] {img_path.name}")

        # Detect barcodes
        barcodes = detect_barcodes(img_path)
        barcode_str = None
        if barcodes:
            barcode_str = barcodes[0]['clean'] or barcodes[0]['raw']
            print(f"  Barcode: {barcode_str} ({barcodes[0]['type']})")
        else:
            print(f"  Barcode: NOT DETECTED")

        # OCR for Part No.
        part_no, ocr_text = ocr_image(img_path)
        if part_no:
            print(f"  Part No: {part_no}")
        else:
            print(f"  Part No: NOT DETECTED")

        # Record result
        result = {
            'file': img_path.name,
            'barcode': barcode_str,
            'part_no': part_no,
            'matched_product': None,
            'updated': False,
        }

        if barcode_str and part_no:
            success += 1

            # Try to match and update
            if products and not dry_run:
                product = find_closest_match(part_no, products)
                if product:
                    result['matched_product'] = product['partNo']
                    if not product.get('barcode'):
                        print(f"  -> Updating {product['partNo']} (row {product['sheetRow']}) with barcode {barcode_str}")
                        try:
                            update_barcode(script_url, product['sheetRow'], barcode_str)
                            result['updated'] = True
                            # Update local cache
                            product['barcode'] = barcode_str
                            time.sleep(0.5)  # Rate limit
                        except Exception as e:
                            print(f"  -> ERROR updating: {e}")
                    else:
                        print(f"  -> {product['partNo']} already has barcode: {product['barcode']}")
                else:
                    print(f"  -> No matching product found for {part_no}")

        elif barcode_str or part_no:
            partial += 1
        else:
            failed += 1

        results.append(result)

    # Summary
    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)
    print(f"Total images:     {len(images)}")
    print(f"Both detected:    {success}")
    print(f"Partial:          {partial}")
    print(f"Nothing detected: {failed}")

    updated_count = sum(1 for r in results if r['updated'])
    if not dry_run and products:
        print(f"Updated in sheet: {updated_count}")

    # Save detailed results to JSON
    report_path = folder / "processing_report.json"
    with open(report_path, 'w') as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"\nDetailed report saved to: {report_path}")

    # Print unmatched items
    unmatched = [r for r in results if r['barcode'] and r['part_no'] and not r['matched_product']]
    if unmatched:
        print(f"\n--- Unmatched items ({len(unmatched)}) ---")
        for r in unmatched:
            print(f"  {r['file']}: Part No={r['part_no']}, Barcode={r['barcode']}")

    # Print items where only one was detected
    partial_items = [r for r in results if bool(r['barcode']) != bool(r['part_no'])]
    if partial_items:
        print(f"\n--- Partial detections ({len(partial_items)}) ---")
        for r in partial_items:
            if r['barcode']:
                print(f"  {r['file']}: Barcode={r['barcode']}, Part No=MISSING")
            else:
                print(f"  {r['file']}: Part No={r['part_no']}, Barcode=MISSING")

    return results


def main():
    parser = argparse.ArgumentParser(description='Batch process Fresenius label photos')
    parser.add_argument('folder', help='Folder containing label photos')
    parser.add_argument('--script-url', help='Apps Script web app URL')
    parser.add_argument('--dry-run', action='store_true',
                        help='Preview only, do not update spreadsheet')

    args = parser.parse_args()

    if not os.path.isdir(args.folder):
        print(f"Error: {args.folder} is not a directory")
        sys.exit(1)

    if not args.dry_run and not args.script_url:
        print("Error: --script-url required unless using --dry-run")
        sys.exit(1)

    process_folder(args.folder, args.script_url, args.dry_run)


if __name__ == '__main__':
    main()
