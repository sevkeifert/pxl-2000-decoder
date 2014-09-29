import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.border.*;
import java.util.Vector;
import java.util.Enumeration;
import java.io.*;
import javax.sound.sampled.*;
import java.awt.font.*;
import java.text.*;
import java.util.*;
import java.awt.image.*;
import javax.imageio.*;

// This is the decoder

public class PXLDecoder implements Runnable {
    
    // constants
    // include debugging code?
    public static final boolean DEBUG = true;
    public static final int CHANNEL_LEFT = 0; 
    public static final int CHANNEL_RIGHT = 1; 

    // cli args
    public static HashMap<String,String> params = new HashMap<String,String>();  
    public static ArrayList<String> argsList = new ArrayList<String>();  

    // file resources
    public File file;
    public AudioInputStream audioInputStream;
    public AudioFormat format;
    double duration, seconds;
    public BufferedImage image;
    public WaveFile wav; 
    public PrintWriter timecode; 

    // audio resources 
    // for audio conversion 192000 -> 44100 @ normal speed 
    TargetDataLine line; // line in
    DataLine.Info info;
    public boolean isLineIn = false;
    double speed = 8d; // speed ~ 8x normal tape in pxl 
    double leftAudioTick = ((44100d/192000d)/speed); // weight of 1 tick sample in 44k, normal speed
    double leftAudioTickSum = 0d; // tally of ticks.  take sample each time sum >= 1
    int leftAudioPtr = 0; // number of samples taken
    int[] leftAudio;  // resampled left channel buffer

    // save data in capture dir
    public final static String captureDir = "capture";
    int captureIdx = 0; // for saving capture

    // image parameters 
    // NOTE: data row is around 230
    // rendered height will be signal height x2, since using top/bottom of wav
    int width = 245;   // peaks per row
    int height = 190;  // rows per image frame
    int imageSize = width * height;
    int colPtr = 0; // is vertical sync
    int rowPtr = 0; // is horizontal sync
    int imagePtr = 0; // is frame sync
    long totalRows = 0;
    // image conversion
    public int highLevel = 13000; // black cutoff
    public int lowLevel = 200; // white cutoff

    // analog signal state, increasing/decreasing?
    boolean asc = true;
    int peakLastValue = 0;
    int lastPeak = 0;
    int ticksX = 0;
    int minTick = 4 ; // @ 192khtz, AM peaks will be 5-6 ticks apart
    int maxTick = 7 ;  

    // signal buffers
    public int bufferSize = 1024 * 64;
    public int audioBufferRead = -1;
    public byte[] audioBuffer = null; // raw audio double bytes (L + R)
    public int[] audioData = null; // converted to signed int (L + R)
    int[] dyData; // first derivative
    int lastY = -1;  
    public int[] peakData = null; // high/low points
    public int[] peakTickData = null; // tick count between high/low points
    public int peakDataCount = 0;
    public int[] peakDeltaData = null; // deltas between peaks (correct for DC offset)
    public int peakDeltaDataCount = 0;
    int[][] pixelData = new int[width][height]; // raw image data
    int[] imageBuffer = new int[imageSize * 3];  // rendered image

    // sync tracking
    int missedSyncCount = 0;
    boolean isSync = false; // detect sync pulse start 
    boolean lostSync = false; // missed last sync pulse 
    int syncSize = 0; // size of sync region  - short 
    int minRowAdvance = 50; // filter out empty black rows, hiccups in sync
    int minFrameAdvance = 50; // filter out empty frames 
    int maxRowNoSync = 150; // how full should row be before looking for sync 
    float baseLine = 15000; // track overall signal level
    float baseLineInertia = 1f/5000; // row is ~ 230.  how fast avg will change
    float syncLevel = baseLine * 2 ;
    float syncLevelInertia = 1f/10f;  // small sync frame ~ 5 points 
    int syncDecayPerTick = 0; // general decay of tracking toward 0 
    float signalTrip = 3.00f; // > multiple of base signal, trip boundary
    int syncSizeThreshhold = 100; // make this number function of signal 
    public static final int freqScale = 2; // scale signal on slower frequency
    
    // get relative size of signal to baseLine... very high or low?
    float pov = 1f;  // peak over value
    float vob = 1f;  // value over baseLine

    // main decoding thread
    public Thread thread;  // for decoding process
    public volatile boolean isRunning = false;

    // gui
    public PXLDecoderGUI gui = null;


    // constructor 
    public PXLDecoder() {

        // tuning params
        minTick = getAsInt("min_tick", minTick);
        maxTick = getAsInt("max_tick", maxTick);

        // command line image params
        width = getAsInt("width", width);
        height = getAsInt("height", height);
        speed = getAsDouble("speed", speed);
        lowLevel = getAsInt("low_level", lowLevel);
        highLevel = getAsInt("high_level", highLevel);
        maxRowNoSync = getAsInt("max_row_no_sync", maxRowNoSync);
        syncSizeThreshhold = getAsInt("sync_threshold", syncSizeThreshhold);
        baseLineInertia = getAsFloat("base_inertia", baseLineInertia);
        syncLevelInertia = getAsFloat("sync_inertia", syncLevelInertia);
        bufferSize = getAsInt("buffer_size", bufferSize);

        if ( DEBUG ) {
            debug("audio buffer size = " + bufferSize);
        }
        audioBuffer = new byte[bufferSize]; 
        leftAudio = new int[bufferSize/2]; 

        setBackgroundColor();

    }


    public static void main(String args[]) throws Exception {

        parseCliArgs(args);

        PXLDecoder pxlDecoder = new PXLDecoder();

        if ( isSet( "cli" ) ) {

            // command line mode
            pxlDecoder.cli();

        } else {

            // by default use gui
            //set look and feel
            try { 

                log("available themes:");
                UIManager.LookAndFeelInfo[] looks = UIManager.getInstalledLookAndFeels();
                if (isSet("theme")) { 
                    // use user theme
                    UIManager.setLookAndFeel( get("theme") ); 

                } else {

                    //try to use nimbus
                    for (UIManager.LookAndFeelInfo look : looks) {
                        String className = look.getClassName();
                        log(" - " + className );
                        if ( className.indexOf("nimbus") > -1 ) {
                            log( "    using nimbus theme as default" );
                            UIManager.setLookAndFeel(className);
                        }
                    }
                }
                
                //log("default system theme: " + UIManager.getSystemLookAndFeelClassName());
                //UIManager.setLookAndFeel( 
                //        UIManager.getSystemLookAndFeelClassName() 
                //); 

            } catch (Exception e) {
                System.out.println("Could not set look and feel");
                System.out.println(e);
            } 

            // wire up decoder to gui
            PXLDecoderGUI gui = new PXLDecoderGUI(pxlDecoder);
            pxlDecoder.setGui(gui);


            if ( argsList.size() > 0 ) {
                pxlDecoder.decodeAudioStream(argsList.get(0));    
            }
        }
    }


    // parse command line switches. supported formats:
    //     boolean flags:  -abc -d -e  
    //     key value pairs:  --key value
    //     bare values:  value 
    public static void parseCliArgs(String args[]) {

        for (int i = 0; i < args.length; i++) {
            switch (args[i].charAt(0)) {
            case '-':
                if ( args[i].length() > 3 && args[i].charAt(1) == '-') {
                    // format:
                    //    --key value
                    String value = args[i].substring(2); // key
                    params.put(value, args[i+1]);
                    i++;
                } else if(args[i].length() > 2) {
                    // format:
                    //   -flag
                    String value = args[i].substring(1); // flag
                    params.put(value,"1");
                }
                break;
            default:
                // bare arg, eg:
                //    filename1 
                argsList.add(args[i]);
                break;
            }
        }

        if ( DEBUG ) {
            title( "params" ) ;
            for (String k : params.keySet() ) {
                log ( "\t" + k + " = " + params.get(k) );
            }    

            title( "arg list" ) ;
            for (String k : argsList ) {
                log ( "\t" + k );
            }    
        }
    }


    // command line params parse utils
    public static boolean isSet( String key ) {
        return ( null != params.get( key ) );
    }

    public static String get( String key ) {
        return params.get(key);
    }

    public static int getAsInt( String key ) {
        return getAsInt(key,-1);
    }

    public static int getAsInt( String key , int defaultValue ) {
        if ( ! isSet(key) ) {
            return defaultValue;
        }
        return Integer.parseInt(get(key));
    }

    public static float getAsFloat( String key ) {
        return getAsFloat( key, -1f);
    }

    public static float getAsFloat( String key , float defaultValue ) {
        if ( ! isSet(key) ) {
            return defaultValue;
        }
        return Float.parseFloat(get(key));
    }    

    public static double getAsDouble( String key ) {
        return getAsDouble(key, -1d);
    }

    public static double getAsDouble( String key , double defaultValue ) {
        if ( ! isSet(key) ) {
            return defaultValue;
        }
        return Double.parseDouble(get(key));
    }    

    public void error(String s ){
        System.out.println(s);
        if ( null == gui ) {
            System.exit(1);    
        } else { 
            gui.setStatus(s);
        }
    }

    // logger utils
    public static void log(){
        log("");    
    }

    //public static boolean debug (){
    //    return isSet("debug");
    //}

    public static void  debug(Object o){
        if ( isSet("debug") ) 
            log(o);
    }


    public static void log (Object s){
        System.out.println(s);
    }

    // public status messages
    public void info(Object o){
        if ( null != gui ) {
            gui.setStatus(o.toString());
        } else {
            System.out.println(o);
        }
    }

    public static void title(String title){
        log();
        log("-------------------------------------------------------");
        log(title);
        log("-------------------------------------------------------");
        log();
    }


    // execute command line mode
    public void cli() {
        title( "cli mode" );

        if (argsList.size() == 0 ) {
            error( "no filename" );
        }

        try {

            String fileName = argsList.get(0);
            debug("open filename " + fileName );
            file = new File(fileName);
            //audioInputStream = AudioSystem.getAudioInputStream(file);
            decodeAudioStream(file);

        } catch ( Exception e) {
            log ( "Error:" );
            error(e.toString());
        }

    }


    // primary methods for file/linein signal -> image
    public void decodeAudioStream(String file) throws Exception {
        decodeAudioStream(new File(file));
    }

    public void decodeAudioStream(File file) throws Exception {
            this.file = file;
            decodeAudioStream();
    }

    public void decodeAudioStream(boolean lineIn) throws Exception {
        setIsLineIn(lineIn);
        decodeAudioStream();
    }

    public void decodeAudioStream() throws Exception {

        if ( ! isLineIn && null == file ) {
            error("no file specified");
            return;
        }

        createCaptureDir();    
        stopDecoding();
        setIsRunning(true);
        thread = new Thread(this);
        thread.start();
    }


    public void setIsRunning(boolean isRunning) {

        if ( isRunning ) {
            info("processing...");
        }

        this.isRunning = isRunning;

        if ( null != gui ) {
            gui.repaintConvertButton();
        }

    }

    public void stopDecoding() {

        info("");

        // cut streams
        lineInClose();
        fileClose();
    
        setIsRunning(false);    
        if ( null != thread ) {
            thread.interrupt();
            thread = null;
            System.gc();    
        }
    }


    // image and audio processing
    public void run() {

        if ( ! isRunning ) {
            return;
        }

        try {

            imagePtr = 0;
            totalRows = 0;
            missedSyncCount = 0;

            setBackgroundColor();
            openTimecode();
            wav = new WaveFile();

            if ( ! isSet("tuning") ) {
                // init new wav
                wav.setFile(getCaptureWaveFile());
                wav.openFile();
                wav.setChannels(1);
                wav.writeHeader();
            }    
        
            do {
                //debug( "audioBufferRead = " + audioBufferRead );

                if ( ! isRunning ) {
                    return;
                }

                readData(); // fill audio buffer from source

                // ignore audio for frames <= 0 (tape not rolling)
                if ( imagePtr > 0 && ! isSet("tuning") ) {
                    // process audio, save data to wav
                    // resample 192k * 8 audio to standard 44k
                    //wav.write(audioBuffer, audioBufferRead);
                    leftAudioPtr = 0;
                    for ( int i = 0 ; i < audioData.length/2; i ++ ) {
                        leftAudioTickSum += leftAudioTick;
                        if ( leftAudioTickSum >= 1 ) {
                            // found eligible 44k sample point
                            int sample = audioData[i*2 + CHANNEL_LEFT];
                            //log("take sample "+leftAudioPtr+": " + sample );
                            leftAudio[leftAudioPtr] = sample; 
                            leftAudioTickSum -= 1;
                            leftAudioPtr++;
                        }
                    }
                    wav.write(leftAudio, leftAudioPtr);
                }

                // process video, save to png
                if ( isSet("am_only") ) {
                    // use only am data
                    // slightly faster, stronger sync pulse, but grainy
                    extractPeaks(audioData,CHANNEL_RIGHT);
                } else {
                    // default: use first derivative
                    // smoother and sharper
                    // but with heavier horizontal lines, weaker sync
                    extractDy(audioData,CHANNEL_RIGHT);
                    extractPeaks(dyData);
                }

                // get relative diff between peaks
                extractPeakDeltas();

                //ALT experimental: process dyData peaks directly -- grainy
                //peakDeltaData=peakData;
                //peakDeltaDataCount=peakDataCount

                extractPixels();

            }    while ( audioBufferRead  > 0 );

            setIsRunning(false);    
            showStats( false );

        } catch ( Exception e ) {

            stopDecoding();
            e.printStackTrace();

        } finally {

            if ( ! isSet("tuning") ) {
                try {
                    // save wav
                    wav.finalize();
                    wav.closeFile();
                } catch ( Exception e ) {
                    info("could not finalize wav file" + e.toString());
                }
            }
            
            saveStats();
            saveConvertScript();
            closeTimecode();
        }
    }


    // read data from file/linein to a buffer
    public int readData() throws Exception {
        if ( ! isRunning ) 
            return -1;

        if ( isLineIn ) {
            return lineInReadData();
        } else {
            return fileReadData();
        }
    }


    // audio file util -- start
    public void fileOpen() throws Exception {

        if ( file == null ) {
            error("no file specified");
            return;
        }

        audioInputStream = AudioSystem.getAudioInputStream(file);

        long milliseconds = (long)((audioInputStream.getFrameLength() * 1000) / audioInputStream.getFormat().getFrameRate());
        duration = milliseconds / 1000.0;

        if ( null == audioInputStream ) {
            error("no audio stream");
        }

        format = audioInputStream.getFormat();
    }

    public void fileClose() {
        // keep file ref for repeat conversion 
        //if ( null != file ) { 
        //      file = null;
        //}
    
        try {
            if ( null != audioInputStream ) {
                audioInputStream.close();
                audioInputStream = null;
            }
        } catch (Exception e) {
            debug(e);
        }
    }


    // reads raw wav data into audio buffer
    // this converts to int, 0xff = -1 = 0xffffffff.  that is why (255 & lsb) is needed
    // cuts samples in half because two bytes per sample
    // this code does not care about left/right...mixes all together
    public int fileReadData() throws Exception {

        if ( null == audioInputStream ) {
            fileOpen();
        }

        audioBufferRead = audioInputStream.read(audioBuffer);

        if ( audioBufferRead == -1 ) {
            // end of stream, auto close
            debug("end of file");
            fileClose();
        } else {
            extractAudioData();
        }

        return audioBufferRead;
    }

    // audio file util -- end


    // line in util -- start
    public void lineInOpen() throws Exception {

        lineInClose();

        // same as wav
        float sampleRate = 192000;
        int sampleSizeInBits = 16;
        int channels = 2;
        boolean signed = true;
        boolean bigEndian = false;

        debug( "get line in" );
        format =  new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);

        info = new DataLine.Info( TargetDataLine.class, format);
        line = (TargetDataLine) AudioSystem.getLine(info);

        debug( "open line in" );
        line.open(format);
        line.start();    
    }


    public void lineInClose() {

        try {

            if ( null != line ) {
                debug( "close line in" );
                line.stop();
                line.drain();
                line.close();    
                line = null;
            }
        } catch (Exception e) {
            debug(e);
        }
    }

    public int lineInReadData() throws Exception {
        if ( null == line ) {
            lineInOpen();
        }

        debug( "reading from line in" );
        audioBufferRead = line.read( audioBuffer, 0, audioBuffer.length );

        debug("read: " + audioBufferRead );
        if ( audioBufferRead == -1 ) {
            debug("end of stream");
            lineInClose();

        } else {
            extractAudioData();
        }

        return audioBufferRead;
    }

    public boolean isLineIn() {
        return isLineIn;
    }

    public void setIsLineIn(boolean isLineIn) {

        info( "switch source to " + (isLineIn?"line":"file"));
        this.isLineIn = isLineIn;
    }
    // line in util -- end


    // convert raw sample bytes from file/linein to AM data
    // store to audioData buffer
    // for stereo, audio packets alternate like LRLRLR
    public void extractAudioData() {

        int nlengthInSamples = 0;
        if (format.getSampleSizeInBits() == 16) {
             nlengthInSamples = audioBufferRead / 2;

             audioData = new int[nlengthInSamples];
             if (format.isBigEndian()) {
                for (int i = 0; i < nlengthInSamples; i++) {
                     // First byte is MSB (high order)
                     int MSB = (int) audioBuffer[2*i];
                     // Second byte is LSB (low order) 
                     int LSB = (int) audioBuffer[2*i+1];
                     audioData[i] = MSB << 8 | (255 & LSB);    
                 }
             } else {
                 // little endian -- standard wave file
                 for (int i = 0; i < nlengthInSamples; i++) {
                     // First byte is LSB (low order) 
                     int LSB = (int) audioBuffer[2*i];
                     // Second byte is MSB (high order) 
                     int MSB = (int) audioBuffer[2*i+1];
                     audioData[i] = MSB << 8 | (255 & LSB);
                 }
             }

         } else if (format.getSampleSizeInBits() == 8) {
            nlengthInSamples = audioBufferRead;

             audioData = new int[nlengthInSamples];
             if (format.getEncoding().toString().startsWith("PCM_SIGN")) {
                 for (int i = 0; i < audioBufferRead; i++) {
                     audioData[i] = audioBuffer[i];
                 }
             } else {
                 for (int i = 0; i < audioBufferRead; i++) {
                     audioData[i] = audioBuffer[i] - 128;
                 }
             }
        }

        // dump raw AM    
        if ( isSet("debug_am") ) {
            title("raw audio data") ;
            log("nlengthInSamples = " + nlengthInSamples);
            log("sample bit size = " + format.getSampleSizeInBits());
            log("big endian = " + format.isBigEndian());
            log("signed = " + format.getEncoding().toString().startsWith("PCM_SIGN"));
            for ( int i = 0 ; i < audioData.length ; i++ ) {
                log( i + ": " + audioData[i]); 
            }
        }  
    }


    // first derivative
    // drop the highLevel and signalTrip to lower values like 60k and 166
    public void extractDy(int[] audioData) throws Exception {
        extractDy(audioData,-1);
    }

    // select channel - L R
    public void extractDy(int[] audioData, int channel) throws Exception {

        if ( ! isRunning ) 
            return;

        if ( audioData == null || audioData.length == 0 ) {
            error("no audio data");
            return;
        }

        int start = 0;
        int inc = 1;
        int packets = 1; // mono

        if ( channel > -1 ) {
            start = channel;
            inc = 2;
            packets = 2; // L and R
        }

        int dyDataSize = audioData.length/packets; 
        dyData = new int[dyDataSize];
        int dIdx=0;

        for ( int i = start ; i < audioData.length; i+=inc ) {
            //dyData[dIdx] = audioData[i] - lastY;
            dyData[dIdx] = audioData[i] - lastY;
            lastY = audioData[i];
            debug(i + "\t" + dyData[dIdx]);
            dIdx++;
        }

        if ( isSet("debug_dy") ) {
            title("first derivative");
            for ( int i = 0 ; i < dyData.length; i++ ) {
                log(i + "\t" + dyData[i]);
            }
        }
    }


    // get inflection points from data buffer
    // only peaks are stored (~2x smaller than 192khtz sampling)
    // at this point, sample rate drops out of math 
    // store to peakData buffer
    public void extractPeaks(int[] data) throws Exception {
        extractPeaks(data, -1);
    }


    public void extractPeaks(int[] data, int channel) throws Exception {
        
        if ( ! isRunning ) 
            return;

        if ( null == data ) {
            error("no audio data");
            return;
        }

        // for stereo data
        int start = 0;
        int inc = 1;
        int packets = 1; // mono
        if ( channel > -1 ) {
            start = channel;
            inc = 2;
            packets = 2; // L and R
        }

        peakData = new int[1 + (data.length/minTick)/packets]; // track peaks
        peakTickData = new int[peakData.length]; // track ticks between peaks
        peakDataCount = 0;
        
        for ( int i = start ; i < data.length; i+=inc ) {
            ticksX ++; // 1-based count
            int value = data[i];
            if ( ticksX >= maxTick ) {

                // lost signal. likely signal too low. add a sample point
                peakData[peakDataCount] = value; 
                peakTickData[peakDataCount] = ticksX; 
                peakDataCount++;
                ticksX = 0;

            } else if ( value > peakLastValue ) {
                // signal is ascending
                if ( ! asc && ticksX >= minTick ) {
                    // found inflection point
                    //debug("X @ " + ticksX + ", value = " + peakLastValue );
                    //peakData[peakDataCount++] = peakLastValue * ticksX;
                    peakData[peakDataCount] = peakLastValue;
                    peakTickData[peakDataCount] = ticksX; 
                    peakDataCount++;
                    ticksX = 0;
                }
                //debug("+ " + value );
                asc = true;    

            } else {
                // signal is descending
                if ( asc && ticksX >= minTick ) {
                    // found inflection point
                    //debug("X @ " + ticksX + ", value = " + peakLastValue );
                    //peakData[peakDataCount++] = peakLastValue * ticksX;
                    peakData[peakDataCount] = peakLastValue;
                    peakTickData[peakDataCount] = ticksX; 
                    peakDataCount++;
                    ticksX = 0;
                }
                //debug("- " + value );
                asc = false;    
            }
            peakLastValue = value; // save in case this was a peak
        }    

        if ( isSet("debug_peak") ) {
            title("extracting peaks ... ");
            for ( int i = 0 ; i < peakDataCount; i++ ) {
                int peak = peakData[i];
            }
            log("peakDataCount = " + peakDataCount );
        }

    }


    // find y-change between peaks (to adjust for DC offset)
    // NOTE: deltas are twice as large as signed signal (up to 64k)
    // store to peakDeltaData  buffer
    public void extractPeakDeltas() throws Exception {

        if ( ! isRunning ) 
            return;

        peakDeltaDataCount = 0;
        if ( null == peakData ) {
            error( "no peak data" );
            return;
        }


        peakDeltaData = new int[peakDataCount]; 
        for ( int i = 0 ; i < peakDataCount; i++ ) {

            int peak = peakData[i];
            int delta = lastPeak - peak;
            peakDeltaData[peakDeltaDataCount++] = delta; 
            lastPeak = peak;        
        }

        if ( isSet("debug_delta") ) {
            title("extract deltas");
            for ( int i = 0 ; i < peakDeltaDataCount; i++ ) {
                int delta = peakDeltaData[i];
                log( i + ": " + delta );
            }
            log("peakDeltaDataCount = " + peakDeltaDataCount );
        }
    }


    // reconstruct image 
    // look at average baseLine of signal for a row and entire image.  
    // sync spikes are signal changes >2x baseLine
    // store to pixelData buffer
    public void extractPixels() throws Exception {

        if ( ! isRunning ) 
            return;

        if ( null == peakDeltaData ) {
            error( "no delta data" );
            return;
        }

        if ( isSet("debug_pixels") ) {
            title("extract pixels");
        }

        for ( int i = 0 ; i < peakDeltaDataCount; i++ ) {

            //int absvalue = Math.abs(value); 
            int pvalue = Math.abs(peakDeltaData[i]); // raw pixel data

            // note: sync signal slows down slightly.  
            // scaled value up by the larger tick counts between peaks
            int ticks    = peakTickData[i];  
            int svalue    = peakDeltaData[i] * (ticks - minTick)^freqScale;
            int absvalue = Math.abs(svalue); 

            // discard bottom signal -- experimental
            //if ( svalue < 0 ) continue;
 
            // track high signal for spike
            // only match on top half of wave
            if ( svalue > syncLevel ){ 
                // hit a high point
                syncLevel = absvalue;

                //if ( isSync ) {
                //    // lock onto highest peak -- experimental 
                //    colPtr = 0;
                //}
                //debug ( " - spike @ " + svalue  );
            }

            // peak over svalue: shows start of sync
            if ( absvalue != 0  ) {
                //pov = syncLevel/svalue; // only match on top signal
                pov = syncLevel/absvalue;
            }

            // svalue over baseLine: shows end of sync
            // get relative sizes
            if ( baseLine != 0  ) {
                //vob = svalue /baseLine; // only top of signal
                vob = absvalue /baseLine; 
            }

            //debug ( "pov " + pov + " vob " + vob ) ;
            if ( vob > signalTrip ) {

                // signal high
                if ( ! isSync && ( colPtr > maxRowNoSync || lostSync )) {

                    // row is at least half full, and we found a spike in the signal
                    isSync = true;
                    lostSync = false;
                    //colPtr = 0;
                    syncSize = 0;
                    //debug("--------------------------- trip start ---------------------------") ;

                }

                //debug( " - signal "+ absvalue +" over baseLine " +baseLine+" by " + vob );

            } else if ( pov > signalTrip ) {

                // signal is very low, found possible spike end  
                //debug( " - signal "+ absvalue +" under syncLevel " + syncLevel +" by " + pov );

                if ( isSync ) {

                    //debug( "----------------------- trip end @ (syncSize="+syncSize+") -----------------------" ) ;

                    // completed row or image 
                    if ( syncSize > syncSizeThreshhold ) {
                        // image sync
                        //debug("found image frame");
                        if ( rowPtr > minFrameAdvance ) {
                            saveImage();
                        }
                        colPtr = 0;
                        rowPtr = 0;

                    } else {
                        // row sync
                        //debug("found row frame");
                        if ( colPtr > minRowAdvance ) {
                            rowPtr++;
                            totalRows++;
                        }
                        colPtr = 0; 
                    }

                    lostSync = false;
                    isSync = false;
                } 

                //baseLinePeakHigh = baseLine; // reset peak
            } 

            // experimental ignore high outliers, want baseline to decay to zero
            // otherwise, just set a high inertia for the baseline
            //    if ( vob < signalTrip ) {
                    // track average baseLine of row, image signal    
            baseLine += (absvalue - baseLine) * baseLineInertia; // short baseLine
            //    }    

            //debug ( colPtr +  ": data " + svalue + " baseLine " +baseLine + " syncLevel " + syncLevel + " vob " + vob + " pov " + pov );
    
            // track ticks between sync
            syncLevel += ( absvalue - syncLevel ) * syncLevelInertia - syncDecayPerTick;

            if ( isSync ) {
                syncSize++;
                //debug( " - syncLevel = " + syncLevel );

            } else {

                // save pixel data
                colPtr++;    
            } 

            if ( rowPtr >= height ) {

                // frame overflow 
                debug( "****** WARN: missed sync: image frame full!  flushing image buffer. *******");

                rowPtr =  0;
                colPtr = 0;
                syncSize = 0;
                isSync = false;
                lostSync = true;
                missedSyncCount ++;

            } else if ( colPtr >= width ) {
            
                // row overflow    
                debug( "****** WARN: missed sync: row full! flushing row buffer. *******");
                rowPtr ++; // count as a full row
                colPtr = 0;
                syncSize = 0;
                isSync = false;
                lostSync = true;
                missedSyncCount ++;

            } else {
                // save data to image buffer
                pixelData[colPtr][rowPtr] = pvalue; 
            }
        }
    }


    // flush pixel buffer to image
    public void saveImage() throws Exception {

        if ( ! isRunning ) 
            return;

        // named imagePtr  
        if ( null == pixelData ) {
            error("no pixel data");
            return;
        }

        imagePtr++;

        if ( isSet("debug") ) {
            title("image data " + imagePtr);
        }

        // safety on capping files per directory
        if ( isSet("max_frames") && imagePtr > getAsInt("max_frames") ) {
            error("too many frames");
            return;
        }

        // render image data to RBG
        int p = 0;

        for (  int y = 0 ; y < height ; y++ ) { //each row
        //for (  int y = 0 ; y < height ; y+=2 ) { // even rows
            for (  int i = 0 ; i < 2 ; i++ ) {  // double lines 
            //for (  int i = 0 ; i < 4 ; i++ ) {  // 4x lines 
                for (  int x = 0 ; x < width ; x++ ) {
                    int value = pixelData[x][y];

                     // scale value to [0 , 255]
                    int rgb = Math.max(0, value - lowLevel ); 
                    if ( highLevel != 0 ) {
                        rgb = Math.min(255, (rgb * 255)/ highLevel ); 
                    }

                    rgb = 255 - rgb;  // negative

                    imageBuffer[p] = rgb;

                    // greyscale
                    p+=1;

                    // for color tinting...
                    //imageBuffer[p+1] = rgb;
                    //imageBuffer[p+2] = rgb;
                    //p+=3;
                }
            }
        }

        //// for color
        //image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // greyscale
        image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        WritableRaster raster = image.getRaster();
        raster.setPixels(0, 0, width, height, imageBuffer);

        repaint();

        if ( ! isSet("no_repaint") ) {
            // wipe pixel data between each frame
            pixelData = new int[width][height];
            setBackgroundColor();
        }

        if ( ! isSet("tuning") ) {
            File outputfile = getCaptureFile();
            ImageIO.write(image, "png", outputfile);
        }

        if ( isSet("debug_pixels") ) {
            title("pixel data");
            for (  int y = 0 ; y < height ; y++ ) {
                for (  int x = 0 ; x < width ; x++ ) {
                    int value = pixelData[x][y];
                    debug(x + "," + y + " = " + value );
                }
            }
        }

        showStats(true);

        // save time when frame is *complete*
        appendTimecode(wav.getAudioSeconds());     

    }  // end save image


    // some getters/setters
    public String getFilename(){
        return file.getName();
    }

    public long getFrameLength() {
        return audioInputStream.getFrameLength();
    }

    public int getFrameSize() {
        return getFormat().getFrameSize();
    }

    public double getSeconds() {
        return seconds;
    }

    public double getDuration() {
        return duration;
    }

    public void setHighLevel( int highLevel ) {
        this.highLevel = highLevel;
    }

    public void setLowLevel( int lowLevel ) {
        this.lowLevel = lowLevel;
    }

    public void setSignalTrip( float signalTrip ) {
        this.signalTrip = signalTrip;
    }

    public int getRenderedWidth() {
        return width;
    }

    public int getRenderedHeight() {
        return height*2;
    }

    public void setBackgroundColor() {
        // wipe background in buffer
        for (  int y = 0 ; y < height ; y++ ) {
            for (  int x = 0 ; x < width ; x++ ) {
                pixelData[x][y] = highLevel * 2;
            }
        }
    }

    public File getFile() {
        return file;
    }

    public void setFile(File f) {
        this.file = f;
    }

    // return format
    public AudioFormat getFormat() {
        return audioInputStream.getFormat();
    }


    // gui / call backs

    public void setGui( PXLDecoderGUI gui ) {
        this.gui = gui;
    }
    
    public void repaint() throws Exception {
        if ( null != gui ) {
            gui.samplingGraph.repaint();
            int sleep = -1;
            if ( (sleep = getAsInt("thread_sleep")) > 0 ) {
                Thread.sleep(sleep);
            }
        }
    }


    // wave
    public File getCaptureWaveFile() {
        String dir = getCaptureDirName();
        return new File( dir + File.separator + "audio.wav" );
    }

    // file save
    public void createCaptureDir() {
        // capture folder
        captureIdx ++;
        String captureDirFull = getCaptureDirName();
        boolean status = new File(captureDirFull).mkdirs();
        if ( ! status ) {
            info("could not create directory: " + captureDirFull);
        }
    }


    public String getCaptureDirName() {
        if ( isSet("capture_dir" ) ) {
            return get("capture_dir"); 
        } else {
            String captureIdxStr = String.format("%05d", captureIdx);
            return captureDir + File.separator + captureIdxStr;
        }
    }


    public File getCaptureFile() {
        String filenameIdx = String.format("%05d", imagePtr);
        String captureDirFull = getCaptureDirName(); 
        return new File(captureDirFull + File.separator + "frame_"+filenameIdx+".png");
        
    }

    public void setSpeed(double speed)
    {
        this.speed = speed;
    }
    
    public double getSpeed()
    {
        return speed;
    }

    // average frames per second
    public double getFps ( ) {
        double seconds = wav.getAudioSeconds();
        double fps = 0; 
        if ( seconds != 0 ) {
            fps = ((double) imagePtr) / seconds; 
        }
        return fps;
    }


    // general progress
    public void showStats(boolean requireGui) {

        if ( requireGui && ( null == gui ) ) {
            return;
        }

        info( getStats() );
    }

    // get stats info
    public String getStats() {
        int avg = 0;
        if ( totalRows > 0 ) {
            avg = (int)((missedSyncCount * 100)/totalRows);
        }
        String stats = "image count: " + imagePtr 
            + "\ntotal rows: " + totalRows
            + "\nmissed sync: " + missedSyncCount + " (~" + avg + "%)"
            + "\nbaseline: " + baseLine 
            + "\nsync signal: " + syncLevel 
            + "\nsec ~: " + (float)wav.getAudioSeconds() 
            + "\nfps ~: " + (float)getFps() 
            + "\nsaved at: " + getCaptureDirName() + File.separator
            ;
        return stats;    
    }

    // save conversion data for later reference
    public void saveStats() {
        writeFile( makeFile("info.txt"), getStats() );
    }

    //very basic converter script
    public void saveConvertScript() {
        String script = "#!/bin/bash\n\n"
            + "avconv -r {0} -i frame_%05d.png -i audio.wav movie.flv";
        script = script.replace( "{0}" , String.valueOf((float)getFps()) );
        writeFile(makeFile( "convert.sh" ), script);
    }

    // string -> file 
    public File makeFile( String filename ) {
        File f = new File(getCaptureDirName() + File.separator + filename);
        return f;
    }

    // general file writer
    public void writeFile( File f, String text ){
        try {
            PrintWriter writer = new PrintWriter(f,  "UTF-8");
            writer.print(text);
            writer.close();
        } catch ( Exception ex ) {
            ex.printStackTrace();
        }
    }

    // save timecode info for reference
    public void openTimecode() {
        closeTimecode();
        try {
            timecode = new PrintWriter(makeFile("timecode.txt"),  "UTF-8");
        } catch ( Exception ex ) {
            ex.printStackTrace();
        }
    }

    public void appendTimecode( double time ) {
        try {
            if ( null == timecode ) {
                openTimecode(); 
            }    
            timecode.println(String.valueOf((float)time));
        } catch ( Exception ex ) {
            ex.printStackTrace();
        }
    }

    public void closeTimecode() {
        try {
            if ( null != timecode ) {
                timecode.close();
            }
           timecode = null;    
        } catch ( Exception ex ) {
            ex.printStackTrace();
        }
    }


} // end class
