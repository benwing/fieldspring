#!/bin/bash

if [ -z $FIELDSPRING_DIR ]; then
    echo "You must set the environment variable FIELDSPRING_DIR to point to Fieldspring's installation directory."
    exit
fi

origwd=`pwd`

if [ ! -e $FIELDSPRING_DIR/data/gazetteers/allCountries.zip ]; then
    mkdir -p $FIELDSPRING_DIR/data/gazetteers
    cd $FIELDSPRING_DIR/data/gazetteers
    wget http://web.corral.tacc.utexas.edu/utcompling/fieldspring-data/allCountries.zip
fi



cd $origwd
