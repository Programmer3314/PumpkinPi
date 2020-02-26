/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.CvSource;
import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoMode;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.EntryListenerFlags;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
       "switched cameras": [
           {
               "name": <virtual camera name>
               "key": <network table key used for selection>
               // if NT value is a string, it's treated as a name
               // if NT value is a double, it's treated as an integer index
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";

  @SuppressWarnings("MemberName")
  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  @SuppressWarnings("MemberName")
  public static class SwitchedCameraConfig {
    public String name;
    public String key;
  };

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();
  public static List<SwitchedCameraConfig> switchedCameraConfigs = new ArrayList<>();
  public static List<VideoSource> cameras = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;

    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read single switched camera configuration.
   */
  public static boolean readSwitchedCameraConfig(JsonObject config) {
    SwitchedCameraConfig cam = new SwitchedCameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read switched camera name");
      return false;
    }
    cam.name = nameElement.getAsString();

    // path
    JsonElement keyElement = config.get("key");
    if (keyElement == null) {
      parseError("switched camera '" + cam.name + "': could not read key");
      return false;
    }
    cam.key = keyElement.getAsString();

    switchedCameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    if (obj.has("switched cameras")) {
      JsonArray switchedCameras = obj.get("switched cameras").getAsJsonArray();
      for (JsonElement camera : switchedCameras) {
        if (!readSwitchedCameraConfig(camera.getAsJsonObject())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    CameraServer inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    
    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Start running the switched camera.
   */
  public static MjpegServer startSwitchedCamera(SwitchedCameraConfig config) {
    System.out.println("Starting switched camera '" + config.name + "' on " + config.key);
    MjpegServer server = CameraServer.getInstance().addSwitchedCamera(config.name);
    server.setCompression(75);
    server.setResolution(320,240);
    
    NetworkTableInstance.getDefault()
        .getEntry(config.key)
        .addListener(event -> {
              if (event.value.isDouble()) {
                int i = (int) event.value.getDouble();
                if (i >= 0 && i < cameras.size()) {
                  server.setSource(cameras.get(i));
                }
              } else if (event.value.isString()) {
                String str = event.value.getString();
                for (int i = 0; i < cameraConfigs.size(); i++) {
                  if (str.equals(cameraConfigs.get(i).name)) {
                    server.setSource(cameras.get(i));
                    break;
                  }
                }
              }
            },
            EntryListenerFlags.kImmediate | EntryListenerFlags.kNew | EntryListenerFlags.kUpdate);

    return server;
  }

  /**
   * Example pipeline.
   */
  public static class MyPipeline implements VisionPipeline {
    public int val;
   
    NetworkTableInstance nt;
    NetworkTable table;

    CvSource source1;
    public MyPipeline(CvSource source1){  
      this.source1 = source1;

      nt = NetworkTableInstance.getDefault();
      table = nt.getTable("Retroreflective Tape Target");
    }

    @Override
    public void process(Mat mat) {
      if(nt.getEntry("PumpkinSwitch").getDouble(0) == 0.0){
        val += 1;

        Point pt1 = new Point(mat.width() / 2 , mat.height());
        Point pt2 = new Point(mat.width() / 2, 0);

        Mat hsv = new Mat();
        Mat inRangeHSV = new Mat();
        Mat dilated = new Mat();

        Size kernelSize = new Size(16,16);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, kernelSize);
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();


        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_BGR2HSV);
        Core.inRange(hsv, 
        // new Scalar(66.0, 100.0, 30.0),       
        // new Scalar(120.0, 255.0, 255.0), inRangeHSV);
        new Scalar(66.0, 90.0, 60.0),       
        new Scalar(100.0, 255.0, 240.0), inRangeHSV);
        // Imgproc.erode(inRangeHSV, eroded, kernel);
        Imgproc.dilate(inRangeHSV, dilated, kernel);
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

        double maxArea = -1;
        int indexAtMax = -1;
        double centerX = -1;
        double centerY = -1;
        for(int i = 0; i< contours.size(); i++){
            MatOfPoint m = contours.get(i);
            Rect r =  Imgproc.boundingRect(m);
            if(r.width > r.height){
              if(r.area() > maxArea){
                maxArea = r.area();
                indexAtMax = i;
                centerX = ((r.x - mat.width() / 2) + 0.5 + (r.width/2));
                centerY = ((r.y - mat.height() / 2) + 0.5 + (r.height/2));
            }
          }
        }
        if(maxArea > -1){
          table.getEntry("Retro x").setNumber(centerX);
          table.getEntry("Retro y").setNumber(centerY);
          table.getEntry("Retroreflective Target Found").setBoolean(true);
          table.getEntry("X Angle").setNumber(pixelToAngle(mat.width(), 70.42, centerX));
        }else{
          table.getEntry("Retroreflective Target Found").setBoolean(false);
        }
        Imgproc.line(mat, pt1, pt2, new Scalar(0,0,255),5);
        Imgproc.drawContours(mat, contours, indexAtMax, new Scalar(255,0,0), 5);

        
          source1.putFrame(mat);
        

        
        hsv.release();
        inRangeHSV.release();
        dilated.release();
    }
      mat.release();
    }

    public double pixelToAngle(double camWidth, double camFOV, double xPosition){
      double f = (0.5 * camWidth) / Math.tan(0.5 * (camFOV * Math.PI / 180.0));
      
      double pixelX = xPosition;
      
      double dot = (f * f);
      double alpha = Math.acos(dot / (f * (Math.sqrt(pixelX * pixelX + f * f))));

      if(xPosition < 0)
        alpha *= -1;
      
      return (alpha * 180.0/Math.PI);
    }

  }


  public static class MyPipelineTwo implements VisionPipeline {
    public int val;
   
    NetworkTableInstance nt;
    NetworkTable table;

    CvSource source1;
    public MyPipelineTwo(CvSource source1){  
      this.source1 = source1;

      nt = NetworkTableInstance.getDefault();
      table = nt.getTable("Retroreflective Tape Target");
    }

    @Override
    public void process(Mat mat) {
      if(nt.getEntry("PumpkinSwitch").getDouble(0) == 1.0){
        source1.putFrame(mat);
      }
    }

    public double pixelToAngle(double camWidth, double camFOV, double xPosition){
      double f = (0.5 * camWidth) / Math.tan(0.5 * (camFOV * Math.PI / 180.0));
      
      double pixelX = xPosition;
      
      double dot = (f * f);
      double alpha = Math.acos(dot / (f * (Math.sqrt(pixelX * pixelX + f * f))));

      if(xPosition < 0)
        alpha *= -1;
      
      return (alpha * 180.0/Math.PI);
    }

  }
  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }

    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }

    // start cameras
    for (CameraConfig config : cameraConfigs) {
      cameras.add(startCamera(config));
    }

    // start switched cameras
    for (SwitchedCameraConfig config : switchedCameraConfigs) {
      startSwitchedCamera(config);
    }
    MjpegServer myStream;
    CvSource source1;

    myStream = new MjpegServer("processedVideo", 1676);
    myStream.setCompression(75);
    myStream.setDefaultCompression(75);
    myStream.setResolution(320, 240);
    source1 = new CvSource("myImage", VideoMode.PixelFormat.kMJPEG,640,480,30);
    myStream.setSource(source1);


    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new MyPipeline(source1), pipeline -> {
        // do something with pipeline results
      });
      /* something like this for GRIP:
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline -> {
        ...
      });
       */
      visionThread.start();
    }

    if (cameras.size() >= 2) {
      VisionThread visionThread2 = new VisionThread(cameras.get(1),
              new MyPipelineTwo(source1), pipeline -> {
        // do something with pipeline results
      });
      /* something like this for GRIP:
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new GripPipeline(), pipeline -> {
        ...
      });
       */
      visionThread2.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
  }
}
