import java.io.*;
import java.io.File;
import javax.sound.sampled.*;

// This is a class for writing wav files
// Basically a wav file is just a stream of L,R bytes with a small header
//
// big/little endian is as follows:
//     strings:  big endian
//     int:      4 bytes little endian
//     short:    2 bytes little endian
//
// see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/
//
// ks 2014 GPL v3 

public class WaveFile {

    // quality
    private long channels = 2; // 1 == mono, 2 == stereo
    private long sampleRate = 44100;
    private int bitsPerSample = 16;


    // data 
    private DataOutputStream outFile;
    private File file;
    private long dataSize = 0;
    int totalSize = 0;


    // constructor    
    public WaveFile() {
    }

    public WaveFile(File file) {
        setFile(file);
    }


    // data utils
    public File getFile()
    {
        return file;
    }
    
    public void setFile(File file)
    {
        this.file = file;
    }

    public void openFile( File file ) throws Exception {
        setFile(file);
        openFile();
    }

    public void openFile() throws Exception {
        outFile = new DataOutputStream(new FileOutputStream(file));
    }

    public void closeFile() throws Exception {
        outFile.close();
        outFile = null;
    }


    // data util
    public void write( String wavData ) throws Exception {
        //log( totalSize + ": write string " + wavData );
        outFile.writeBytes(wavData); // be
        totalSize += wavData.length();    
    }

    // int -> byte conversion
    public void write( int[] wavData, int size ) throws Exception {

        //log( totalSize + ": writing wav data int array, size =" + size );    
        //if ( size > 4 ) {
        //    log( " - " + wavData[0]  + "," + wavData[1] + "," + wavData[2] + "," + wavData[3] +  " ... " + wavData[wavData.length-4]  + "," + wavData[wavData.length-3] + "," + wavData[wavData.length-2] + "," + wavData[wavData.length-1] );    
        //}

        // reverse previous byte conversion 
        byte[] wavBytes = new byte[size * 2];
        for (int i = 0; i < size; i++) {
            byte MSB = (byte) ((wavData[i] & 0xFF00) >> 8);
            byte LSB = (byte) (wavData[i] & 0x00FF);
            wavBytes[2*i] = LSB ;  // le
            wavBytes[2*i+1] = MSB ;
            //wavBytes[2*i] = MSB ; // be
            //wavBytes[2*i+1] = LSB ;
         }
        write(wavBytes,wavBytes.length);
        totalSize += wavBytes.length; 
    }

    public void write( byte[] wavData, int size ) throws Exception {
        //log( totalSize + ": writing wav bytes, size = " + size);    
        //if ( size > 4 ) {
        //    log( " - " + wavData[0]  + "," + wavData[1] + "," + wavData[2] + "," + wavData[3] +  " ... " + wavData[wavData.length-4]  + "," + wavData[wavData.length-3] + "," + wavData[wavData.length-2] + "," + wavData[wavData.length-1] );    
        //}

        outFile.write(wavData,0, size); // LRLR wav bytes 
        dataSize += size;
        totalSize += size; 
    }

    public void writeInt( int wavData ) throws Exception {
        //log( totalSize + ": writing int " + wavData);    
        outFile.write(intToByteArray((int)wavData)); 
        totalSize += 4;
    }

    public void writeShort( short wavData ) throws Exception {
        //log( totalSize + ": writing short " + wavData);    
        outFile.write(shortToByteArray((short)wavData)); 
        totalSize += 2; 
    }


    // 4 bytes, little endian
    public byte[] intToByteArray(int value) {
        byte[] b = new byte[4];
        for (int i = 0; i < b.length; i++) {
            int offset = i * 8;
            b[i] = (byte) ((value >>> offset) & 0xFF);
            //log( " - " + b[i]  );
        }
        return b;
    }

    // 2 bytes, little endian
    public byte[] shortToByteArray(short s) {
        byte[] b = new byte[] { 
            (byte) (s & 0x00FF),(byte) ((s & 0xFF00) >>> 8) 
        };
        //log( " - " + b[0]  );
        //log( " - " + b[1]  );
        return b;
    }


    //wav header  
    // all strings big endian
    // all int/short little endian
    public void writeHeader() throws Exception {

        write("RIFF");  // 0    4: chunkId Contains the letters "RIFF" in ASCII form (0x52494646 big-endian form).    
        writeInt((int)0);  // 4    4: ChunkSize       totalSize -8
        write("WAVE"); // 8    4: Format           Contains the letters "WAVE" (0x57415645 big-endian form).
        write("fmt "); // 12    4: Subchunk1ID      Contains the letters "fmt "  (0x666d7420 big-endian form).
        writeInt((int)16);    // 16    4 Subchunk1Size    16 for PCM.  This is the size of the rest of the Subchunk which follows this number.    
        writeShort((short)1);        // 20   2   AudioFormat      PCM = 1 (i.e. Linear quantization) Values other than 1 indicate some form of compression.  
        writeShort((short)channels);        // 22   2   NumChannels      Mono = 1, Stereo = 2, etc. 
        writeInt((int)sampleRate);        // 24   4   SampleRate       8000, 44100, etc. 
        writeInt((int)(sampleRate * channels * bitsPerSample) / 8); // 28        4   ByteRate         == SampleRate * NumChannels * BitsPerSample/8 
        writeShort((short)((channels * bitsPerSample)/8)); //32        2   BlockAlign       == NumChannels * BitsPerSample/8 
        writeShort((short)bitsPerSample); // 34        2   BitsPerSample    8 bits = 8, 16 bits = 16, etc.
        write("data");  // 36        4   Subchunk2ID      Contains the letters "data" (0x64617461 big-endian form).    
        writeInt((int)0);  // 40        4   Subchunk2Size    == NumSamples * NumChannels * BitsPerSample/8 

        // write actual data here ...
    }

    //update header bytes with final counts after finished
    public void finalize() throws Exception {

        if ( dataSize == 0 )  {
            throw new Exception("no data written");
        }

        //log( "total audio data = " + dataSize );
        //log( "total audio size = " + totalSize );

        // update the header
        RandomAccessFile out = new RandomAccessFile(file, "rw");
        try {
            //update subChunk1Size

            //log( "update byte @ 4 = " + (totalSize-8) );
            out.seek(4);
            out.write(intToByteArray((int)totalSize-8)); // dataSize + header - first two sections 
            // update chunk size
            out.seek(42);
            //log( "update byte @ 42 = " + dataSize  );
            out.write(intToByteArray((int) dataSize));  // the data
        } catch (Exception e) { 
            e.printStackTrace();
            throw e;
        } finally { 
            try {
                if ( null != out ) {
                    out.close();
                }
            } catch (Exception e) { 
                e.printStackTrace();
            }
        }
    } 


    // 44100    
    public long getSampleRate()
    {
        return sampleRate;
    }
    
    public void setSampleRate(long sampleRate)
    {
        this.sampleRate = sampleRate;
    }

    // 16    
    public int getBitsPerSample()
    {
        return bitsPerSample;
    }
    
    public void setBitsPerSample(int bitsPerSample)
    {
        this.bitsPerSample = bitsPerSample;
    }

    // mono = 1, stereo = 2
    public long getChannels()
    {
        return channels;
    }

    public void setChannels(long channels)
    {
        this.channels = channels;
    }


    // stdout/debug 
    public static void log(Object o) {
        System.out.println(o);
    }
    

    // how long is file?
    public double getAudioSeconds() {
        return ( (double)(dataSize / (bitsPerSample/8) )
                        /getSampleRate())
                        /getChannels();
    } 


}
