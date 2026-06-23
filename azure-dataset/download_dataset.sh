#!/bin/bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
INPUT_DIR="$DIR/input"
GREEN='\033[0;32m'
NC='\033[0m'

usage() {
  cat <<EOF
Usage: $(basename "$0") --azure | --huawei

Options:
  --azure    Download and extract the Azure Functions 2019 dataset.
  --huawei   Download and extract the Huawei private 2023 minute datasets.
  -h, --help Show this help message.
EOF
}

download_azure_dataset() {
  local archive="$INPUT_DIR/azurefunctions-dataset2019.tar.xz"
  local urls=(
    "https://azurepublicdatasettraces.blob.core.windows.net/azurepublicdatasetv2/azurefunctions_dataset2019/azurefunctions-dataset2019.tar.xz"
    "https://azurecloudpublicdataset2.blob.core.windows.net/azurepublicdatasetv2/azurefunctions_dataset2019/azurefunctions-dataset2019.tar.xz"
  )

  mkdir -p "$INPUT_DIR"

  echo -e "${GREEN}Downloading Azure dataset...${NC}"
  for url in "${urls[@]}"; do
    if wget -O "$archive" "$url"; then
      break
    fi
    rm -f "$archive"
  done

  if [[ ! -f "$archive" ]]; then
    echo "Failed to download Azure dataset from all known URLs."
    exit 1
  fi
  echo -e "${GREEN}Downloading Azure dataset...done${NC}"

  echo -e "${GREEN}Extracting Azure dataset...${NC}"
  tar -xvf "$archive" -C "$INPUT_DIR"
  rm "$archive"
  echo -e "${GREEN}Extracting Azure dataset...done${NC}"
  echo -e "${GREEN}Check the ./input directory for the dataset files.${NC}"
}

download_huawei_dataset() {
  local target_dir="$INPUT_DIR/huawei_private_minute"
  local download_dir="$target_dir/_downloads"
  local gdown_cmd=("gdown")
  local files=(
    "requests_minute:1W9dQvqRGylfUeHJcYqWwJrpYHIsYCVq_"
    "function_delay_minute:1Ooentp5nUeC2qKig8QSvJAaRgk3Kix_v"
    "memory_limit_minute:1COvoR8VwQnwxstOHuJj_YjlSaMv_jDLT"
  )

  mkdir -p "$download_dir"

  if ! command -v gdown >/dev/null 2>&1; then
    if ! python3 -m gdown --version >/dev/null 2>&1; then
      echo "gdown not found. Install with: pip3 install --user gdown"
      exit 1
    fi
    gdown_cmd=("python3" "-m" "gdown")
  fi

  echo -e "${GREEN}Downloading Huawei private 2023 minute datasets...${NC}"
  for file in "${files[@]}"; do
    local name="${file%%:*}"
    local id="${file#*:}"
    "${gdown_cmd[@]}" "$id" -O "$download_dir/$name.zip"
  done
  echo -e "${GREEN}Downloading Huawei private 2023 minute datasets...done${NC}"

  echo -e "${GREEN}Extracting Huawei private 2023 minute datasets...${NC}"
  for zip_file in "$download_dir"/*.zip; do
    unzip -q "$zip_file" -d "$target_dir"
  done
  echo -e "${GREEN}Extracting Huawei private 2023 minute datasets...done${NC}"
  echo -e "${GREEN}Huawei private 2023 minute datasets downloaded to: $target_dir${NC}"
}

if [[ $# -ne 1 ]]; then
  usage
  exit 1
fi

case "$1" in
  --azure)
    download_azure_dataset
    ;;
  --huawei)
    download_huawei_dataset
    ;;
  -h | --help)
    usage
    ;;
  *)
    usage
    exit 1
    ;;
esac
