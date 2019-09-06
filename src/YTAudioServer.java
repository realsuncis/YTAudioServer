import com.sun.media.codec.audio.mp3.JS_MP3ConversionProvider;
import com.sun.media.codec.audio.mp3.JS_MP3DecoderStream;
import com.sun.media.codec.audio.mp3.JS_MP3FileReader;
import javafx.embed.swing.JFXPanel;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.jcodec.common.io.IOUtils;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.*;
import java.util.*;
import java.net.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

// Server class 
public class YTAudioServer
{
    static final String youtubeDLPath = "C:\\Users\\arman\\Documents\\libs\\youtube-dl.exe";
    static final String ffprobePath = "C:\\Users\\arman\\Documents\\libs\\ffprobe.exe";
    private static ArrayList<String> activeKeys = new ArrayList<>();
    private static ArrayList<String> keys = new ArrayList<>();

    public static void main(String[] args) throws IOException
    {
        ServerSocket ss = new ServerSocket(8080);


        try(BufferedReader br = new BufferedReader(new FileReader("keys.txt"))) {

            String line = null;
            while ((line = br.readLine()) != null) {
                keys.add(line);
            }
        }
        // running infinite loop for getting 
        // client request 
        while (true)
        {
            Socket s = null;

            try
            {
                // socket object to receive incoming client requests 
                s = ss.accept();

                System.out.println("A new client is connected : " + s);

                // obtaining input and out streams 
                DataInputStream dis = new DataInputStream(s.getInputStream());
                DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                System.out.println("Assigning new thread for this client");

                // create a new thread object 
                Thread t = new ClientHandler(s, dis, dos);
                t.start();



            }
            catch (Exception e){
                s.close();
                e.printStackTrace();
            }
        }
    }

    synchronized static boolean isValidKey(String key)
    {
        if(keys.contains(key))
        {
            if(!activeKeys.contains(key))
            {
                activeKeys.add(key);
                return true;
            }
            else
            {
                return false;
            }
        }
        else
        {
            return false;
        }
    }

    synchronized static void removeKey(String key)
    {
        activeKeys.remove(key);
    }
}

// ClientHandler class 
class ClientHandler extends Thread
{
    final DataInputStream dis;
    final DataOutputStream dos;
    final Socket s;


    // Constructor 
    public ClientHandler(Socket s, DataInputStream dis, DataOutputStream dos)
    {
        this.s = s;
        this.dis = dis;
        this.dos = dos;
    }

    @Override
    public void run()
    {
        //Validate if client has rights to connection
        String pass = "";
        try {
            pass = dis.readUTF();
            if(!YTAudioServer.isValidKey(pass))
            {
                dos.writeByte(5);
                System.out.println("Client presented invalid or active key: " + pass + " " + s);
                return;
            }
            else
            {
                dos.writeByte(4);
                System.out.println("Client presented valid key: " + s);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }


        Thread cWriter = new ClientWriter(s, dos);
        cWriter.start();


        //listen for operations
        while (true)
        {
            try {


                byte opCode = dis.readByte();

                switch(opCode)
                {
                    case 0: //End connection
                        ((ClientWriter) cWriter).stopThread();
                        System.out.println("Connection closed on socket: " + s);
                        break;
                    case 2: //Start audio stream
                        String link = dis.readUTF();
                        ((ClientWriter) cWriter).addJob(new Job(OPCODE.START_STREAM, new Object[]{link}));
                        System.out.println("Starting stream: " + link + s);
                        break;
                    case 3: //Seek audio
                        String link1 = dis.readUTF();
                        int timeStamp = dis.readInt();
                        int seconds = dis.readInt();
                        ((ClientWriter) cWriter).addJob(new Job(OPCODE.SEND_AUDIO_DURATION, new Object[]{link1, timeStamp, seconds}));
                    default:

                }

            } catch (IOException e) {
                ((ClientWriter) cWriter).stopThread();
                System.out.println("Connection terminated on socket: " + s);
                break;
            }
        }

        YTAudioServer.removeKey(pass);
        cWriter = null;
        try
        {
            // closing resources
            this.dis.close();
            this.dos.close();

        }catch(IOException e){
            System.out.println("Error closing I/O streams");
            System.exit(1);
        }
    }
}

class ClientWriter extends Thread
{
    BlockingQueue<Job> jobs = new ArrayBlockingQueue<Job>(1);
    final DataOutputStream dos;
    final Socket s;
    Thread audioStreamHandler;
    private boolean runThread = true;
    Map<String, Thread> threadPool = new HashMap<String, Thread>();


    // Constructor
    public ClientWriter(Socket s, DataOutputStream dos)
    {
        this.s = s;
        this.dos = dos;
    }

    @Override
    public void run()
    {
        while (true) {
            Job job;
            try {
                job = jobs.take();
            } catch (InterruptedException e) {
                return;
            }

            switch (job.opcode) {
                case START_STREAM:
                    try {
                        String link = (String)job.data[0];
                        Thread audioThread = new AudioStreamHandler(link, this);
                        audioThread.start();
                        threadPool.put(link, audioThread);
                        int audioDuration = ((AudioStreamHandler)audioThread).getAudioDuration();
                        dos.writeByte(4);
                        dos.writeInt(audioDuration);

                    } catch (InitializationFailedException e) {
                        try {
                            dos.writeByte(5);
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }
                        continue;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                case SEND_AUDIO_DURATION:
                    String link = (String)job.data[0];
                    Thread audioThread = threadPool.get(link);
                    int timeStamp = (int)job.data[1];
                    int seconds = (int)job.data[2];
                    try {
                        FileContainer[] mp3Files = ((AudioStreamHandler)audioThread).getAudioData(timeStamp, seconds);
                        dos.writeByte(4);
                        dos.writeInt(mp3Files.length);
                        for(int i = 0; i < mp3Files.length; i++)
                        {
                            int byteCount = mp3Files[i].getFileBytes().length;
                            System.out.println("File" + i + " size: " + byteCount);
                            dos.writeInt(byteCount);
                            dos.write(mp3Files[i].getFileBytes());
                        }
                    } catch (NoAudioDataException e) {
                        try {
                            dos.writeByte(5);
                        } catch (IOException e1) {
                            e1.printStackTrace();
                        }
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                default:
            }
        }

    }

    synchronized void finalizeThread(String link)
    {
        threadPool.remove(link);

    }

    synchronized void addJob(Job job)
    {
        jobs.offer(job);
    }

    public void stopThread()
    {
        if(audioStreamHandler != null)
        {
            ((AudioStreamHandler) audioStreamHandler).interrupt();
        }
        this.interrupt();
    }

}

class AudioStreamHandler extends Thread
{

    private final String link;
    private volatile int audioDuration = -1;
    private volatile FileContainer[] mp3FileArray;
    private Object syncObject = new Object();
    ClientWriter parent;

    AudioStreamHandler(String link, ClientWriter parent) throws InitializationFailedException {
        if(link.matches("[a-zA-Z0-9]{11}"))
        {
            this.link = link;
        }
        else throw new InitializationFailedException();

        this.parent = parent;
    }

    @Override
    public void run() {

        String getDurationCommand = "cmd /c \"C:\\Users\\arman\\Documents\\libs\\youtube-dl.exe\" " + "--get-duration " + link;
        Process getDurationProcess;
        try {
            getDurationProcess = Runtime.getRuntime().exec(getDurationCommand);
        } catch (IOException e) {
            synchronized (syncObject) {
                syncObject.notify();
            }
            e.printStackTrace();
            return;
        }

        InputStream is = getDurationProcess.getInputStream();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String duration = br.readLine();
            if(duration.length() <= 2)
            {
                audioDuration = Integer.parseInt(duration);
            }
            else
            {
                String[] durationSplit = duration.split(":");
                int minutes = Integer.parseInt(durationSplit[0]);
                int seconds = Integer.parseInt(durationSplit[1]);
                audioDuration = minutes * 60 + seconds;
            }
            synchronized (syncObject) {
                syncObject.notify();
            }

        } catch (IOException e) {
            synchronized (syncObject) {
                syncObject.notify();
            }
            e.printStackTrace();
            return;
        }

        mp3FileArray = new FileContainer[audioDuration];

        Path checkPath = Paths.get(System.getProperty("user.dir") + "\\mp3\\"+link+"_" + "0.mp3");
        if(!Files.exists(checkPath))//Check if audio doesn't exist already
        {
            String audioStreamCommand = "cmd /c start /B \"\" \"C:\\Users\\arman\\Documents\\libs\\youtube-dl.exe\" -q -o - \"" + link + " \" | \"C:\\Users\\arman\\Documents\\libs\\ffmpeg.exe\" -loglevel quiet -i pipe:0 -q:a 0 -map a -map_metadata -1 -f segment -segment_time 1 " + System.getProperty("user.dir") + "\\mp3\\" + link + "_%01d.mp3";
            Process audioProcess;
            try {
                audioProcess = Runtime.getRuntime().exec(audioStreamCommand);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }

        for(int i = 0; i < audioDuration; i++)
        {
            Path mp3Path = Paths.get(System.getProperty("user.dir") + "\\mp3\\"+link+"_" + i + ".mp3");
            while(!Files.exists(mp3Path)) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            try {
                mp3FileArray[i] = new FileContainer(mp3Path);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        System.out.println("Stream finished: " + link);
        //parent.finalizeThread(link);

    }

    public int getAudioDuration() {
        synchronized (syncObject)
        {
            try
            {
                if (audioDuration == -1) syncObject.wait();
                return audioDuration;
            }
            catch (InterruptedException e)
            {
                return -1;
            }
        }

    }

    public FileContainer[] getAudioData(int timeStamp, int seconds) throws NoAudioDataException
    {
        if(timeStamp >=audioDuration) throw  new NoAudioDataException();
        int difference = audioDuration - (timeStamp + seconds);
        if (difference < 0) seconds = audioDuration - timeStamp;

        FileContainer[] files = new FileContainer[seconds];
        for(int i = timeStamp; i < timeStamp + seconds; i++)
        {
            while(mp3FileArray[i] == null) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            files[i-timeStamp] = mp3FileArray[i];
        }

        return files;
    }

    /*synchronized boolean enqueueLink (String link)
    {
        if (link.matches("[a-zA-Z0-9]{11}"))
        {
            audioLinks.offer(link);
            return true;
        }
        else
        {
            return false;
        }
    }*/

}