#!/usr/bin/env bash
# usage: snip.sh <file> <tag>   — mirrors grackle.docs.Output.snip
file="$1"; tag="$2"
awk -v tag="$tag" 'index($0,tag)>0 {c++; next} c==1 {print}' "$file"
