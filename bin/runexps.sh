#!/bin/bash

VERBOSE=no
DEBUG=no
MEMORY=
WIKITAG=enwiki-20131104
WIKILOGSUFFIX=nbayes-dirichlet
FSOPTS=

while true ; do
  case "$1" in
    #--help ) help; exit 1 ;;
    #--quiet ) quiet=yes; shift ;;

    -wikitag | --wikitag ) WIKITAG="$2"; shift 2 ;;
    -wiki-log-suffix | --wiki-log-suffix | -wikilogsuffix | --wikilogsuffix )
      WIKILOGSUFFIX="$2"; shift 2 ;;
    ## Options passed to fieldspring
    -minheap | --minheap ) FSOPTS="$FSOPTS --minheap $2"; shift 2 ;;
    -maxheap | --maxheap ) FSOPTS="$FSOPTS --maxheap $2"; shift 2 ;;
    -memory | --memory ) FSOPTS="$FSOPTS --memory $2"; shift 2 ;;
    -escape-analysis | --escape-analysis )
      FSOPTS="$FSOPTS --escape-analysis"; shift ;;
    -compressed-oops | --compressed-oops )
      FSOPTS="$FSOPTS --compressed-oops"; shift ;;
    -verbose | --verbose ) VERBOSE=yes; FSOPTS="$FSOPTS --verbose"; shift ;;
    -debug-jvm | --debug-jvm ) FSOPTS="$FSOPTS --debug"; shift ;;
    -- ) shift ; break ;;
    * ) break ;;
  esac
done

if [ -n "$WIKILOGSUFFIX" ]; then
  WIKILOGSUFFIX="-$WIKILOGSUFFIX"
fi

corpusname=$1; # tr or cwar
split=$2; # dev or test
topidmethod=$3; # gt or ner
modelsdir=wistr-models-$WIKITAG-$corpusname$split-gt/;
listrmodelsdir=listr-models-$WIKITAG-$corpusname$split-gt/;
wistrlistrmodelsdir=wistrlistr-models-$WIKITAG-$corpusname$split-gt/;
if [ $corpusname == "cwar" ]; then
    sercorpusprefix=cwar
else
    sercorpusprefix=trf
fi
if [ $corpusname == "cwar" ]; then
    sercorpussuffix="-20spd"
else
    sercorpussuffix=""
fi
sercorpusfile=$sercorpusprefix$split-$topidmethod-g1dpc$sercorpussuffix.ser.gz;
corpusdir=${4%/}/$split/; # fourth argument is path to corpus in XML format
if [ $corpusname == "cwar" ]; then
    logfileprefix=cwar
else
    logfileprefix=trf
fi
if [ $corpusname == "cwar" ]; then
    logfilesuffix="-20spd"
else
    logfilesuffix=""
fi

logfile=$WIKITAG-$logfileprefix$split$logfilesuffix-g1dpc-100$WIKILOGSUFFIX.log;

function prettyprint {
    if [ $topidmethod == "ner" ]; then
        echo $1 "&" $2 "&" $3 "&" $4
    else
        echo $1 "&" $2 "&" $3 "&" $4 "&" $5
    fi
}

function getr1 {
    if [ $topidmethod == "ner" ]; then
        echo `grep -A50 "$1" temp-results.txt | grep "P: " | tail -1 | sed -e 's/^.*: //'`
    else
        echo `grep -A50 "$1" temp-results.txt | grep "Mean error distance (km): " | tail -1 | sed -e 's/^.*: //'`
    fi
}

function getr2 {
    if [ $topidmethod == "ner" ]; then
        echo `grep -A50 "$1" temp-results.txt | grep "R: " | tail -1 | sed -e 's/^.*: //'`
    else
        echo `grep -A50 "$1" temp-results.txt | grep "Median error distance (km): " | tail -1 | sed -e 's/^.*: //'`
    fi
}

function getr3 {
    echo `grep -A50 "$1" temp-results.txt | grep "F: " | tail -1 | sed -e 's/^.*: //'`
}

function getr4 {
    echo `grep -A50 "$1" temp-results.txt | grep "Fraction of distances within 161 km: " | tail -1 | sed -e 's/^.*: //'`
}

function printres {

    r1=`getr1 $1`
    r2=`getr2 $2`
    r3=`getr3 $3`
    r4=`getr4 $4`

    prettyprint $1 $r1 $r2 $r3 $r4

}

if [ -e temp-results.txt ]; then
    rm temp-results.txt
fi

execute()
{
  RUNCMD="$1"; shift
  echo Executing: $RUNCMD ${1+"$@"} >> temp-results.txt
  $RUNCMD ${1+"$@"} >> temp-results.txt
}

dofieldspring()
{
  execute fieldspring $FSOPTS ${1+"$@"}
}

shift 4
methods="$@"
if [ -z "$1" -o "$1" = all ]; then
  methods="oracle rand population bmd spider tripdl wistr prob-prelim wistr+spider trawl trawl+spider listr wistr+listr-mix wistr+listr-backoff"
fi

for method in $methods; do

case $method in

oracle )
echo "\oracle" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -r random -oracle
printres "\oracle"
;;

rand )
r1=""
r2=""
r3=""
r4=""
for i in 1 2 3
do
  echo "\rand"$i >> temp-results.txt
  dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -r random
  r1+=`getr1 "\rand$i"`" "
  r2+=`getr2 "\rand$i"`" "
  r3+=`getr3 "\rand$i"`" "
  r4+=`getr4 "\rand$i"`" "
done
r1=`fieldspring run opennlp.fieldspring.tr.util.Average $r1`
r2=`fieldspring run opennlp.fieldspring.tr.util.Average $r2`
r3=`fieldspring run opennlp.fieldspring.tr.util.Average $r3`
r4=`fieldspring run opennlp.fieldspring.tr.util.Average $r4`
prettyprint "\rand" $r1 $r2 $r3 $r4
;;

population )
echo "\population" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -r pop
printres "\population"
;;

bmd )
r1=""
r2=""
r3=""
r4=""
for i in 1 2 3
do
  echo "bmd"$i >> temp-results.txt
  dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -r wmd -it 1
  r1+=`getr1 "bmd$i"`" "
  r2+=`getr2 "bmd$i"`" "
  r3+=`getr3 "bmd$i"`" "
  r4+=`getr4 "bmd$i"`" "
done
r1=`fieldspring run opennlp.fieldspring.tr.util.Average $r1`
r2=`fieldspring run opennlp.fieldspring.tr.util.Average $r2`
r3=`fieldspring run opennlp.fieldspring.tr.util.Average $r3`
r4=`fieldspring run opennlp.fieldspring.tr.util.Average $r4`
echo -n "\\"
prettyprint "bmd" $r1 $r2 $r3 $r4
;;

spider )
r1=""
r2=""
r3=""
r4=""
for i in 1 2 3
do
  echo "spider"$i >> temp-results.txt
  dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -r wmd -it 10
  r1+=`getr1 "spider$i"`" "
  r2+=`getr2 "spider$i"`" "
  r3+=`getr3 "spider$i"`" "
  r4+=`getr4 "spider$i"`" "
done
r1=`fieldspring run opennlp.fieldspring.tr.util.Average $r1`
r2=`fieldspring run opennlp.fieldspring.tr.util.Average $r2`
r3=`fieldspring run opennlp.fieldspring.tr.util.Average $r3`
r4=`fieldspring run opennlp.fieldspring.tr.util.Average $r4`
prettyprint "\spider" $r1 $r2 $r3 $r4
;;

tripdl )
echo "\tripdl" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -l $logfile -r prob -pdg
printres "\tripdl"
;;

wistr )
echo "\wistr" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -l $logfile -r maxent
printres "\wistr"
;;

prob-prelim )
echo "Necessary for next step:" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -l $logfile -r prob -pme
;;

wistr+spider )
echo "\wistr+\spider" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -l $logfile -r wmd -it 10 -rwf
r1=`getr1 "wistr+"`
r2=`getr2 "wistr+"`
r3=`getr3 "wistr+"`
r4=`getr4 "wistr+"`
prettyprint "\wistr+\spider" $r1 $r2 $r3 $r4
;;

trawl )
echo "\trawl" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -l $logfile -r prob
printres "\trawl"
;;

trawl+spider )
echo "\trawl+\spider" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -l $logfile -r wmd -it 10 -rwf
r1=`getr1 "\trawl+"`
r2=`getr2 "\trawl+"`
r3=`getr3 "\trawl+"`
r4=`getr4 "\trawl+"`
prettyprint "\trawl+\spider" $r1 $r2 $r3 $r4
;;

text-construct-tpp-grid )
#echo "TextConstructTPPGrid" >> temp-results.txt
#dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -r constructiontpp -dpc 10
#printres "TextConstructTPPGrid"
;;

text-construct-tpp-cluster )
#echo "TextConstructTPPCluster" >> temp-results.txt
#dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -r constructiontpp -t 250
#printres "TextConstructTPPCluster"
;;

text-aco-tpp-grid )
#echo "TextACOTPPGrid" >> temp-results.txt
#dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -r acotpp -dpc 10
#printres "TextACOTPPGrid"
;;

text-aco-tpp-cluster )
#echo "TextACOTPPCluster" >> temp-results.txt
#dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $modelsdir -r acotpp -t 250
#printres "TextACOTPPCluster"
;;

listr )
echo "\listr" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $listrmodelsdir -l $logfile -r maxent
printres "\listr"
;;

wistr+listr-mix )
echo "\wistr+\listr$_{Mix}$" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $wistrlistrmodelsdir -l $logfile -r maxent
r1=`getr1 "Mix"`
r2=`getr2 "Mix"`
r3=`getr3 "Mix"`
r4=`getr4 "Mix"`
prettyprint "\wistr+\listr{Mix}" $r1 $r2 $r3 $r4
;;

wistr+listr-backoff )
echo "\wistr+\listr$_{Boff}$" >> temp-results.txt
dofieldspring resolve -i $corpusdir -sci $sercorpusfile -cf tr -im $listrmodelsdir:$modelsdir -l $logfile -r maxent
r1=`getr1 "Boff"`
r2=`getr2 "Boff"`
r3=`getr3 "Boff"`
r4=`getr4 "Boff"`
prettyprint "\wistr+\listr{Backoff}" $r1 $r2 $r3 $r4
;;

* )
echo "Unrecognized method $method"
;;

esac
done
