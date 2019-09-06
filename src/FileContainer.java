import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileContainer {

    private byte[] fileBytes;

    FileContainer(Path path) throws IOException
    {
        fileBytes = Files.readAllBytes(path);
    }

    public byte[] getFileBytes()
    {
        return  fileBytes;
    }

}
