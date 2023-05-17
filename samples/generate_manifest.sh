#!/bin/bash

set -x

# This script untars release artifacts and puts them into a manifest to be used for signing

# Ensure a tar argument is passed to this script
if [ $# -eq 0 ]; then
    echo "No arguments supplied"
    exit 1
fi

maindir="/root/signing-client/"
currentdir=$(pwd)

if [[ $1 == *.tgz ]]; then
    artifacts=$(basename "${1%.tgz*}")
else
    artifacts=$(basename "${1%.tar*}")
fi

# Untar to tmp directory and navigate into the root location where the artifacts are located
tmp_dir=$(echo "$artifacts"-tmp)
mkdir $tmp_dir
tar -xvf "$1" -C $tmp_dir
pushd $tmp_dir

# first check for unsigned rpms
shastring="Header V3 RSA/SHA256 Signature, key ID"     
nokey="NOKEY"                                                                                
for i in `find . -name "*.rpm" -type f`; do            
    echo "$i"                                          
    signedrpm=$(rpm -Kvv "$i")                         
    if [[ $signedrpm == *"$shastring"* ]]; then        
        echo "rpm successfully signed...continue"      
        echo $i >> signed_rpms.txt                      
        if [[ "$signedrpm" == *"$nokey"* ]]; then      
            echo "nokey found"                         
            echo "NOKEY Found: "$i"" >> unsigned_rpms.txt                         
        fi                                            
    else
        echo "rpm not signed"
        echo "No sha256: "$i"" >> unsigned_rpms.txt
    fi
done
# move missing rpm list so it doesn't get deleted
if [ -f ./unsigned_rpms.txt ]; then
    mv ./unsigned_rpms.txt "$currentdir"
fi                                                

# Loop through artifacts directory and place each file path and sha into a manifest
shopt -s globstar
for file in ./**; do
    if [ -f "$file" ]; then
        # gets relative path and checksum to add manifest
        echo "$(find "$file") $(openssl sha256 "$(readlink -f "./$file")" | awk '{print $NF}')"
    fi
done >"$currentdir"/"$artifacts"-manifest.txt

popd
"$maindir"/validate_manifest.sh "$currentdir"/"$tmp_dir" "$currentdir"/"$artifacts"-manifest.txt
# cleanup
rm -rf "$tmp_dir"
