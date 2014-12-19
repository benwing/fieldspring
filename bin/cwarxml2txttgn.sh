#!/bin/bash

indir=${1%/}
outdir=${2%/}

if [ ! -e $outdir ]; then
    mkdir $outdir
fi

for f in $indir/*.xml
do
  filename=$(basename $f)
  filename=${filename%.*}
  grep '<milestone unit="sentence"' $f | perl -pe 's/<placeName[^>]*tgn,([0-9]+)"[^>]*>([^<]+)/<placeName>tgn,$1-$2-]]/g' | perl -pe 's/tgn,([^"]+)-(\w+) (\w+)-]]/tgn,\1-\2-\3-]]/g' | perl -pe 's/<[^<>]*>//g' > $outdir/$filename.txt
done
