#!/bin/bash

#rm *.class 
#rm *.jar 
#rm frame_*.png 
#rm -rf capture/*

echo `date`

javac *.java
jar -cvfm PXLDecoder.jar MANIFEST.MF *.class
java  -jar PXLDecoder.jar $@
