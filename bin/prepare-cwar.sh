#!/bin/bash

if [ -z $FIELDSPRING_DIR ]; then
    echo "You must set the environment variable FIELDSPRING_DIR to point to Fieldspring's installation directory."
    exit
fi

origcwarxmldir=
pathtokml=
pathtotgn=
pathtogaz=
cwarxmloutdir=
keeptmp=no

# Handle command-line args for us.
while true ; do
  case "$1" in
    -c | --corpus ) origcwarxmldir="${2%/}"; shift 2 ;;
    -k | --kml ) pathtokml="$2"; shift 2 ;;
    -t | --tgn ) pathtotgn="$2"; shift 2 ;;
    -g | --gaz ) pathtogaz="$2"; shift 2 ;;
    -o | --outdir ) cwarxmloutdir="${2%/}"; shift 2 ;;
    --keep-tmp ) keeptmp=yes; shift ;;
    * ) break ;;
  esac
done

echo "Converting original Cwar corpus to plain format..."
cwarxml2txttgn.sh $origcwarxmldir cwarplaintgn
echo "Splitting corpus into dev and test sets..."
fieldspring --memory 2g run opennlp.fieldspring.tr.app.SplitDevTest cwarplaintgn
if [ ! -e $cwarxmloutdir ]; then
    mkdir $cwarxmloutdir
fi
if [ ! -e $cwarxmloutdir/dev ]; then
    mkdir $cwarxmloutdir/dev
fi
if [ ! -e $cwarxmloutdir/test ]; then
    mkdir $cwarxmloutdir/test
fi

pathtotgncvt=
coordargs=
if [ -n "$pathtotgn" ]; then
  echo "Converting TGN coordinates to a simple text file..."
  pathtotgncvt=tgn-coords.$$.txt
  if echo "$pathtotgn" | grep '\.bz2$' > /dev/null ; then
    bzcat "$pathtotgn" | convert-TGNOut_Coordinates.nt.sh > $pathtotgncvt
  else
    convert-TGNOut_Coordinates.nt.sh < "$pathtotgn" > $pathtotgncvt
  fi
  coordargs="$coordargs -t $pathtotgncvt"
fi
if [ -n "$pathtokml" ]; then
  coordargs="$coordargs -k $pathtokml"
fi

echo "Converting dev corpus to Fieldspring format..."
fieldspring --memory 15g run opennlp.fieldspring.tr.app.ConvertCwarToGoldCorpus -c cwarplaintgndev $coordargs -g $pathtogaz > $cwarxmloutdir/dev/cwar-dev.xml
echo "Converting test corpus to Fieldspring format..."
fieldspring --memory 15g run opennlp.fieldspring.tr.app.ConvertCwarToGoldCorpus -c cwarplaintgntest $coordargs -g $pathtogaz > $cwarxmloutdir/test/cwar-test.xml

if [ "$keeptmp" != "yes" ]; then
  echo "Deleting temporary files..."
  rm -rf cwarplaintgn
  rm -rf cwarplaintgndev
  rm -rf cwarplaintgntest
  if [ -n "$pathtotgncvt" ]; then
    rm -rf $pathtotgncvt
  fi
fi

echo "Done."
