import java.io.File;


public class ls {
	
	public static void main(String[] args) {
		for (String arg : args) {
			int i = 0;
			File f = new File(arg);
			System.out.println("ls " + f);
			for (File child : f.listFiles())
				System.out.println("  " +  ++i + " Child: " + child);
		}
	}

}
