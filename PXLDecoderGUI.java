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

// This is the GUI (Swing)

public class PXLDecoderGUI extends JPanel implements ActionListener { 

    JFrame frame;
    PreviewImage samplingGraph;
    FormatControls formatControls ;
    JLabel status;

    // frame size
    int WIDTH = 640;
    int HEIGHT = 480;
    public static final int SPACER = 20;

    PXLDecoder pxlDecoder;


    public PXLDecoderGUI(PXLDecoder pxlDecoder) {

        this.pxlDecoder = pxlDecoder;

        // left-right container
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));

        JPanel previewPanel = new JPanel(new BorderLayout());
        JPanel controlPanel = new JPanel();
        JPanel statusPanel = new JPanel();

        previewPanel.setBorder(BorderFactory.createTitledBorder("Preview"));
        controlPanel.setBorder(BorderFactory.createTitledBorder("Convert"));

        previewPanel.add(samplingGraph = new PreviewImage());
        controlPanel.add(formatControls = new FormatControls());
        controlPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

        samplingGraph.setSize(pxlDecoder.getRenderedWidth() + SPACER, pxlDecoder.getRenderedHeight() + SPACER);

        add(previewPanel);
        add(controlPanel);

        formatControls.add(statusPanel);

        // status
        statusPanel.add(status = new JLabel());

        openFrame();
    }


    public void openFrame() {
        frame = new JFrame("PXL Decoder");
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { System.exit(0); }
        });
        frame.getContentPane().add("Center", this);
        frame.pack();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(screenSize.width/2 - WIDTH/2, screenSize.height/2 - HEIGHT/2);
        frame.setSize(WIDTH, HEIGHT);
        //frame.show();
        frame.setVisible(true); 
    }

    public void repaintConvertButton() {
        formatControls.buttonConvert.setText(formatControls.getConvertText());
    }

    public void actionPerformed(ActionEvent e) {
    }


    //loads audio stream from file
    public void createAudioInputStream(File f, boolean updateComponents) {
        if (f != null && f.isFile()) {
            try {
                pxlDecoder.setFile(f);
                if (updateComponents) {
                    samplingGraph.drawPreview();
                }
            } catch (Exception ex) { 
                reportStatus(ex.toString());
                ex.printStackTrace();
            }
        } else {
            reportStatus("Audio file required.");
        }
    }

  
    private void reportStatus(String msg) {
        System.out.println(msg);
        samplingGraph.repaint();
    }


    public void setStatus(String s) {
        status.setText("<html><pre>"+s+"</pre></html>");
    }


    ///////////////////////////////////////////////////
    //left video controls panel ...sliders and buttons
    ///////////////////////////////////////////////////
    class FormatControls extends JPanel implements ActionListener, ChangeListener {
    
        Vector groups = new Vector();


        JSlider sliderLastUsed = null;

        JSlider sliderHigh; // for black contrast
        JSlider sliderLow; // for white contrast
        JSlider sliderTrip; // ration of high/low signal to count as sync event
        JSlider sliderMinRow; // protect row pixels 
        JSlider sliderBaseInertia; // growth of baseline avg 
        JSlider sliderSyncInertia; // growth of sync avg 
        JSlider sliderSpeed; // audio resample speed 
        JSlider sliderMisc; // misc - for tuning tests
        //JSlider sliderMaxRow; // protect row pixels 

        JButton buttonLoad;
        JButton buttonLineIn;
        JButton buttonConvert;

        JTabbedPane tabbedPane = new JTabbedPane();
        JPanel tab1 = new JPanel();
        JPanel tab2 = new JPanel();
        JPanel tab3 = new JPanel();


        // toggle button
        public String getConvertText() {
            return "start/stop";
        }

        public void repaintConvertButton() {
            buttonConvert.setText(getConvertText());
        }

        public FormatControls() {

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); 

            //add buttons controls .. across
            JPanel sourcePanel = new JPanel();
            sourcePanel.add(buttonLoad = button("wav file", true));
            sourcePanel.add(buttonLineIn = button("line in", true));
            sourcePanel.add(buttonConvert = button(getConvertText(), true));
            add(sourcePanel);

            add(tabbedPane);

            //add slider controls ... down
            // color
            tab1.add(sliderHigh = slider("high level (black)", 0, 64000, (int)pxlDecoder.highLevel));
            tab1.add(sliderLow = slider("low level (white)", 0, 64000, (int)pxlDecoder.lowLevel));

            // audio
            tab2.add(sliderSpeed = slider("speed", 7000, 10000, (int)(1000 * pxlDecoder.speed), 1000)); 

            // sync
            tab3.add(sliderMinRow = slider("min row size", 0, 500, (int)pxlDecoder.maxRowNoSync)); 
            tab3.add(sliderBaseInertia = slider("base signal inertia", 1, 10000, (int) (1f/pxlDecoder.baseLineInertia))); 
            tab3.add(sliderSyncInertia = slider("sync signal inertia", 1, 10000, (int)(1f/pxlDecoder.syncLevelInertia))); 
            tab3.add(sliderTrip = slider("trip ratio", 0, 1000, (int)(pxlDecoder.signalTrip * 100),100)); 

            // tabs
            tab1.setLayout(new BoxLayout(tab1, BoxLayout.Y_AXIS)); 
            tab1.setBorder(new EmptyBorder(SPACER/2,0,0,0));
            tab2.setLayout(new BoxLayout(tab2, BoxLayout.Y_AXIS)); 
            tab2.setBorder(new EmptyBorder(SPACER/2,0,0,0));
            tab3.setLayout(new BoxLayout(tab3, BoxLayout.Y_AXIS)); 
            tab3.setBorder(new EmptyBorder(SPACER/2,0,0,0));
            tabbedPane.addTab("Image", tab1);    
            tabbedPane.addTab("Audio", tab2);    
            tabbedPane.addTab("Sync", tab3);    

            // for misc tuning tests
            //add(sliderMisc = slider("misc", 0, 1000, (int)pxlDecoder.maxRowSize)); 
        }


        //formats the slider components
        public JSlider slider(String title, int low, int high, int value){
            return slider(title, low, high, value, 1);
        }

        public JSlider slider(String title, int low, int high, int value, int scale){
            JSlider slider1 = new JSlider(JSlider.HORIZONTAL, low, high, value);
            TitledBorder tb1 = new TitledBorder(new EtchedBorder());
            if ( scale == 1 ) {
                tb1.setTitle(title + " = " + value);
            } else {
                tb1.setTitle(title + " = " + (((float)value)/scale));
            }
            slider1.setBorder(tb1);
            slider1.addChangeListener(this);
            return slider1;
        }

        private JButton button(String name, boolean state) {
            JButton b = new JButton(name);
            b.addActionListener(this);
            b.setEnabled(state);
            return b;
        }


        public void alert(String msg){
            JOptionPane.showMessageDialog(null, msg, "Applet Info", JOptionPane.INFORMATION_MESSAGE);
        }


        // callbacks
        public void actionPerformed(ActionEvent e) {
            JButton button = (JButton) e.getSource();

            try {
                if (button.equals(buttonLoad)) {

                    File file = new File(System.getProperty("user.dir"));
                    JFileChooser fc = new JFileChooser(file);
                    fc.setFileFilter(new javax.swing.filechooser.FileFilter () {
                        public boolean accept(File f) {
                            if (f.isDirectory()) {
                                return true;
                            }
                            String name = f.getName();
                            if (name.endsWith(".au") || name.endsWith(".wav") || name.endsWith(".aiff") || name.endsWith(".aif")) {
                                return true;
                            }
                            return false;
                        }
                        public String getDescription() {
                            return ".wav, .aif, .au";
                        }
                    });

                    if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        createAudioInputStream(fc.getSelectedFile(), true);
                    }

                    pxlDecoder.setIsLineIn(false);

                } else if ( button.equals(buttonConvert) )  {

                    if ( pxlDecoder.isRunning ) {
                        pxlDecoder.stopDecoding();
                    } else {
                        pxlDecoder.decodeAudioStream(pxlDecoder.getFile());
                    }

                    repaintConvertButton();

                } else if ( button.equals(buttonLineIn) )  {
                    pxlDecoder.setIsLineIn(true);
                }

            } catch (SecurityException ex) { 
                alert("Permission problem: Could not access files or audio system.");
                ex.printStackTrace();
            } catch (Exception ex) { 
                ex.printStackTrace();
            }

            if ( null != sliderLastUsed ){
                // restore focus to last tuning parameter
                sliderLastUsed.requestFocus(false); 
            }
        }


        public void stateChanged(ChangeEvent e) {
            JSlider slider = (JSlider) e.getSource();
            int value = slider.getValue();
            TitledBorder tb = (TitledBorder) slider.getBorder();
            String s = tb.getTitle();

            sliderLastUsed = slider; // save focus
            int scale = 1;

            if ( slider.equals( sliderLow )  ) {
                pxlDecoder.setLowLevel(value);
            } else if ( slider.equals( sliderHigh )  ) {
                pxlDecoder.setHighLevel(value);
            } else if ( slider.equals( sliderTrip )  ) {
                scale = 100;
                pxlDecoder.setSignalTrip(((float)value)/scale);
            } else if ( slider.equals( sliderMinRow )  ) {
                pxlDecoder.maxRowNoSync = value;
            } else if ( slider.equals( sliderBaseInertia )  ) {
                pxlDecoder.baseLineInertia = 1f/value;
            } else if ( slider.equals( sliderSyncInertia )  ) {
                pxlDecoder.syncLevelInertia = 1f/value;
            } else if ( slider.equals( sliderSpeed )  ) {
                scale = 1000;
                pxlDecoder.speed = ((double)value)/scale;

            //} else if ( slider.equals( sliderMisc )  ) {
            //// for experimental testing
            //    pxlDecoder.maxRowSize = value;
            }

            s = s.substring(0, s.indexOf('=')+2) + s.valueOf(((float)value)/scale);
            tb.setTitle(s);

            slider.repaint();
        }


    } // End class FormatControls



    ///////////////////////////////////
    //right image window panel
    ///////////////////////////////////

    class PreviewImage extends JPanel {

        private Thread thread;
        private Font font10 = new Font("serif", Font.PLAIN, 10);
        private Font font12 = new Font("serif", Font.PLAIN, 12);
        Color jfcBlue = new Color(204, 204, 255);
        Color pink = new Color(255, 175, 175);
 
        public boolean isPreview = true;  // show image

        public PreviewImage() {
            setBackground(new Color(0, 0, 0));
        }

        public void drawPreview() {
            isPreview = true;
        }

        public void paint(Graphics g) {

            super.paintComponent(g);

            if ( isPreview ) {

                int x = 0;
                int y = 0;
                if ( null != pxlDecoder.image ) {
                    x = (getWidth() - pxlDecoder.image.getWidth(null))/2;
                    y = (getHeight() - pxlDecoder.image.getHeight(null))/2;
                }
                g.drawImage(pxlDecoder.image, x, y, null); 
            }
        }

    } // End class PreviewImage


}

