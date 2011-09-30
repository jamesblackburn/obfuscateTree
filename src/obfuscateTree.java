import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Creates a filesystem tree 'equivalent' to an existing fs tree (apart from
 * randomised names).
 *  - All segments are the same length
 *  - All segment names are randomised and cached.
 *    => There are the same number of unique segment strings in tree
 *  - Extension is preserved
 */
public class obfuscateTree {

	/** Map from segment name to randomised segment name */
	public static Map<String,String> obfusSegments = new HashMap<String,String>();
	/** Reverse map of the above */
	public static Map<String,String> revObfusSegments = new HashMap<String,String>();

	private static Set<File> symlinks = new HashSet<File>();

	static {
		obfusSegments.put("source", "source");
		revObfusSegments.put("source", "source");
		obfusSegments.put("build", "build");
		revObfusSegments.put("build", "build");
		obfusSegments.put("results", "results");
		revObfusSegments.put("results", "results");
	}

	static File cwd = new File(".");
	static File input;
	static File output;

	public static void main(String[] args) throws Exception {
		input = new File(args[0]);
		output = new File(args[1]);
		if (!input.isAbsolute())
			input = new File(cwd, args[0]);
		if (!output.isAbsolute())
			output = new File(cwd, args[1]);
		System.out.println("Input: " + input);
		System.out.println("Output: " + output);

		if (!input.exists() || !input.isDirectory())
			throw new Exception("Input directory " + input + " doesn't exist!");

		if (output.exists())
			throw new Exception("Output directory " + output + " already exists!");

		output.mkdir();

		// Recurse over the tree
		recurse (input, output);

		// Recreate the symlinks
		symlinks();
		
		System.out.println("All done!");
		System.out.println(obfusSegments.size() + " unique segments");
		int size = 0;
		for (String s : obfusSegments.keySet())
			size += s.length();
		System.out.println("  ==>  " + size + " characters");
		System.out.println(symlinks.size() + " symlinks");
	}

	private static void recurse(File in, File out) throws Exception {
		assert in.isDirectory();
		assert out.isDirectory();
		System.out.println("Processing " + in + " => "  + out);
		for (File f : in.listFiles()) {
			String origName = f.getName();

			// fix-up symlinks at the end
			if (isSymlink(f)) {
				symlinks.add(f);
				continue;
			}

			// Get the random name
			String outName = getRandomName(origName);

			if (f.isFile())
				new File(out, outName).createNewFile();
			else if (f.isDirectory()) {
				File outDir = new File(out, outName);
				outDir.mkdir();
				recurse (f, outDir);
			}
			else
				System.out.println("Broken link(?): " + f);
		}
	}

	/**
	 * Recreate the symlinks -- make them relative to the directory they're contained in.
	 * Symlinks which can't be made relative are simply ignored.
	 * @throws Exception
	 */
	private static void symlinks() throws Exception {
		// re-create the symlinks in the new place
		System.out.println("Symlinks!");
		for (File s : symlinks) {
			System.out.println("Symlink: " + s.getAbsolutePath() + " => " + s.getCanonicalPath());

			IPath symPath = new Path(s.getAbsolutePath());
			IPath destPath = new Path(s.getCanonicalPath());

			int matchinSegments = destPath.matchingFirstSegments(symPath);
			if (matchinSegments < new Path(input.getAbsolutePath()).segmentCount()) {
				System.out.println("No common sub-path for " + s);
				continue;
			}

			// Work out the working directory
			IPath wd = symPath.removeLastSegments(1).removeFirstSegments(
									new Path(input.getAbsolutePath()).matchingFirstSegments(symPath));
			File workingDirectory = output;
			for (String segment : wd.segments())
				workingDirectory = new File (workingDirectory, getRandomName(segment));
			assert workingDirectory.exists();


			// Create a relative sym-link:
			// We want a symlink relative to the containing directory (rather than absolute)
			// so we can tar up and save this tree

			int dotDotCount = symPath.removeFirstSegments(matchinSegments).segmentCount() - 1;
			assert dotDotCount >= 0;

			StringBuilder sb = new StringBuilder();
			while (dotDotCount-- > 0)
				sb.append("../");
			destPath = destPath.removeFirstSegments(matchinSegments);
			for (String seg : destPath.segments())
				sb.append(getRandomName(seg.toString())).append("/");
			sb.setLength(sb.length() - 1);

			System.out.println("      " + sb.toString());

			// Create the symlink
			ProcessBuilder pb = new ProcessBuilder("ln", "-s", sb.toString(), getRandomName(symPath.lastSegment()));
			Process p = null;
			try {
				pb.directory(workingDirectory);
				p = pb.start();
				p.waitFor();
			} finally {
				if (p != null)
					p.destroy();
			}

			assert isSymlink(new File(workingDirectory, getRandomName(symPath.lastSegment())));
		}
	}

	static char[] characters = "abcdefghijklmnopqrstuvwyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890,".toCharArray();

	/**
	 * Gets a random name, preserving the extension
	 * @param orig
	 * @return
	 */
	public static String getRandomName(String orig) {
		// remove and re-add any extension
		String extension = orig.contains(".") ? orig.substring(orig.lastIndexOf(".")) : null;
		if (extension != null) {
			orig = orig.substring(0, orig.length() - extension.length());
			if (extension.length() > 4)
				System.out.println("WARNING: long extension " + extension);
		}

		if (obfusSegments.containsKey(orig))
			return obfusSegments.get(orig) + (extension != null ? extension : "");

		Random r = new Random(System.currentTimeMillis());
		StringBuilder sb;
		do {
			sb = new StringBuilder();
			for (int i = 0; i < orig.length(); i++)
				sb.append(characters[r.nextInt(characters.length)]);
		} while (revObfusSegments.containsKey(sb.toString()));

		// Stash the keys an values
		obfusSegments.put(orig, sb.toString());
		revObfusSegments.put(sb.toString(), orig);

		return sb.toString() + (extension != null ? extension : "");
	}

	public static boolean isSymlink(File file) throws IOException {
		if (file == null)
			throw new NullPointerException("File must not be null");
		File canon;
		if (file.getParent() == null) {
			canon = file;
		} else {
			File canonDir = file.getParentFile().getCanonicalFile();
			canon = new File(canonDir, file.getName());
		}
		return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
	}

}
