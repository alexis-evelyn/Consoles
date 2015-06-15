package jarcode.consoles.computer.interpreter;

import com.google.common.base.Joiner;
import jarcode.consoles.Consoles;
import jarcode.consoles.computer.*;
import jarcode.consoles.computer.bin.TouchProgram;
import jarcode.consoles.computer.filesystem.FSBlock;
import jarcode.consoles.computer.filesystem.FSFile;
import jarcode.consoles.computer.interpreter.func.TwoArgFunc;
import jarcode.consoles.computer.interpreter.libraries.Libraries;
import jarcode.consoles.computer.interpreter.types.*;
import jarcode.consoles.internal.ConsoleFeed;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.bukkit.ChatColor;
import org.luaj.vm2.*;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.*;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**

This class handles the creation and sandbox of the LuaVM

 */
@SuppressWarnings({"unused", "SpellCheckingInspection"})
public abstract class SandboxProgram {

	public static final Supplier<SandboxProgram> FACTORY = InterpretedProgram::new;
	public static final TwoArgFunc<SandboxProgram, FSFile, String> FILE_FACTORY = InterpretedProgram::new;

	private static final Charset CHARSET = Charset.forName("UTF-8");

	static {
		Libraries.init();
		LuaTypes.init();
	}

	/**
	 * Executes a lua program from the plugin folder, on a specific computer.
	 *
	 * @param path the path to the program, relative to the plugin folder
	 * @param terminal the terminal to run the program on
	 * @param args the arguments for the program
	 * @return true if the program was executed, false if the terminal was busy,
	 * or if something went wrong when loading the file.
	 */
	public static boolean execFile(String path, Terminal terminal, String args) {
		File file = new File(Consoles.getInstance().getDataFolder().getAbsolutePath()
				+ File.separatorChar + path);
		if (Consoles.debug)
			Consoles.getInstance().getLogger().info("Executing file: " + path);
		if (!file.exists() || file.isDirectory()) {
			return false;
		}
		try {
			String program = FileUtils.readFileToString(file, CHARSET);
			return exec(program, terminal, args);
		}
		catch (IOException e) {
			Consoles.getInstance().getLogger().warning("Failed to read lua program from plugin folder: " + path);
			e.printStackTrace();
			return false;
		}
	}

	public static boolean execFile(String path, Terminal terminal) {
		return execFile(path, terminal, "");
	}

	/**
	 * Compiles and runs the given Lua program. The program is ran
	 * with elevated permissions.
	 *
	 * This is not suitable for constant execution of Lua code, as it
	 * has to compile and sandbox the code each time.
	 *
	 * The program that is ran will occupy the current terminal instance
	 * for the computer.
	 *
	 * The directory of the program will be the current directory of
	 * the terminal
	 *
	 * @param program the string that contains the Lua program
	 * @param terminal the terminal to run the program on
	 * @param args the arguments for the program
	 * @return true if the program was executed, false if the terminal was busy
	 */
	public static boolean exec(String program, Terminal terminal, String args) {
		if (terminal.isBusy())
			return false;
		Computer computer = terminal.getComputer();
		SandboxProgram sandboxProgram = FACTORY.get();
		sandboxProgram.restricted = false;
		sandboxProgram.contextTerminal = terminal;
		ProgramInstance instance = new ProgramInstance(sandboxProgram, "", computer, program);

		terminal.setProgramInstance(instance);

		terminal.setIO(instance.in, instance.out, ConsoleFeed.UTF_ENCODER);
		terminal.startFeed();

		instance.start();

		return true;
	}

	public static boolean exec(String program, Terminal terminal) {
		return exec(program, terminal, "");
	}

	public static void pass(String program, Terminal terminal, ProgramInstance instance) {
		pass(program, terminal, instance, "");
	}

	public static void pass(String program, Terminal terminal, ProgramInstance instance, String args) {
		SandboxProgram inst = FACTORY.get();
		inst.restricted = false;
		inst.contextTerminal = terminal;
		instance.interpreted = inst;
		inst.runRaw(instance.stdout, instance.stdin, args, terminal.getComputer(), instance, program);
	}

	public Map<Integer, LuaFrame> framePool = new HashMap<>();
	
	protected FSFile file;
	protected String path;
	protected InputStream in;
	protected OutputStream out;
	protected Computer computer;
	protected FuncPool pool;
	protected String args;
	protected SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	protected List<Integer> allocatedSessions = new ArrayList<>();
	protected EmbeddedGlobals globals;

	protected Runnable terminator;
	protected BooleanSupplier terminated;

	protected InterruptLib interruptLib = new InterruptLib(this::terminated);

	protected List<String> registeredChannels = new ArrayList<>();

	protected boolean restricted = true;

	protected Terminal contextTerminal = null;

	// normal constructor for loading a program from a computer
	public SandboxProgram(FSFile file, String path) {
		this.file = file;
		this.path = path;
	}

	// for program instances that need to be setup for Lua programs
	// initiated through Java code
	protected SandboxProgram() {}

	private void setup(OutputStream out, InputStream in, String str, Computer computer, ProgramInstance instance) {
		this.in = in;
		this.out = out;
		this.computer = computer;
		if (instance != null) {
			this.terminated = instance::isTerminated;
			this.terminator = instance::terminate;
		}
		this.args = str;
	}

	// used to run programs from a file in a computer
	public void run(OutputStream out, InputStream in, String str, Computer computer,
	                ProgramInstance instance) throws Exception {

		setup(out, in, str, computer, instance);

		// if the file is null, something went wrong
		if (file == null) {
			print("null file");
			return;
		}

		// read from the program file and write it to a buffer
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try (InputStream is = file.createInput()) {
			int i;
			while (true) {
				if (terminated())
					break;
				if (is.available() > 0 || is instanceof ByteArrayInputStream) {
					i = is.read();
					if (i == -1) break;
					buf.write(i);
				} else Thread.sleep(50);
			}
			if (terminated())
				print(" [PARSE TERMINATED]");
		}

		// parse as a string
		String raw = new String(buf.toByteArray(), CHARSET);

		compileAndExecute(raw);
	}

	protected abstract void map();

	public void runRaw(OutputStream out, InputStream in, String str, Computer computer,
	                   ProgramInstance inst, String raw) {
		setup(out, in, str, computer, inst);
		compileAndExecute(raw);
	}

	// runs a program with the given raw text
	public void compileAndExecute(String raw) {
		try {

			if (Consoles.debug)
				Consoles.getInstance().getLogger().info("[DEBUG] compiling and running program " +
						"(charlen: " + raw.length() + ")");

			if (contextTerminal == null) {
				contextTerminal = computer.getTerminal(this);
			}

			// this is our function pool, which are a bunch of LuaFunctions mapped to strings
			// we also use this pool to identify our program with this thread.
			//
			// all static functions that were already mapped are automatically added to this pool
			pool = new FuncPool(Thread.currentThread(), this);

			// map functions from this program instance to the pool
			map();

			// create our globals for Lua. We use a special kind of globals
			// that allows us to finalize variables.
			globals = new EmbeddedGlobals();

			// Load libraries from LuaJ. I left a bunch of libraries from the
			// JSE standards to have less possibilities for users to exploit
			// them.
			globals.load(new JseBaseLib());
			globals.load(new PackageLib());
			globals.load(new Bit32Lib());
			globals.load(new TableLib());
			globals.load(new StringLib());
			globals.load(new BaseLib());

			// I added a missing function to the math library
			globals.load(new EmbeddedMathLib());

			// Load our debugging library, which is used to terminate the program
			globals.load(interruptLib);

			// Load any extra libraries, these can be registered by other plugins
			// Note, we only register libraries that are not restricted.
			Lua.libraries.values().stream()
					.filter((lib) -> !lib.isRestricted || !restricted)
					.forEach((lib) -> globals.load(lib.buildLibrary()));

			if (!restricted) {
				globals.load(new CoroutineLib());
				globals.load(new OsLib());
			}

			// install
			LoadState.install(globals);
			LuaC.install(globals);

			// Block some functions
			globals.set("load", LuaValue.NIL);
			globals.set("loadfile", LuaValue.NIL);
			// require should be used instead
			globals.set("dofile", LuaValue.NIL);

			// load functions from our pool
			for (Map.Entry<String, LibFunction> entry : pool.functions.entrySet()) {
				globals.set(entry.getKey(), entry.getValue());
			}

			// set stdout
			if (out == null)
				globals.STDOUT = dummyPrintStream();
			else
				globals.STDOUT = new PrintStream(out);

			// we handle errors with exceptions, so this will always be a dummy writer.
			globals.STDERR = dummyPrintStream();

			// set stdin
			if (in == null)
				globals.STDIN = dummyInputStream();
			else
				globals.STDIN = in;

			// finalize all entries. This means programs cannot modify any created
			// globals at this point.
			globals.finalizeEntries();

			// our main program chunk
			LuaValue chunk;

			// the exit function, if it exists.
			LuaValue exit = null;

			try {

				// try to load in the program
				// this will try to compile the Lua string into Java bytecode
				chunk = globals.load(raw);

			}
			// if we run into a compile error, print out the details and exit.
			catch (LuaError err) {
				if (Consoles.debug)
					err.printStackTrace();
				println("lua:" + ChatColor.RED + " compile error");
				String msg = Arrays.asList(err.getMessage().split("\n")).stream()
						.map(this::warning)
						.collect(Collectors.joining("\n"));
				print(msg);
				return;
			}
			// if we got to this point, the program compiled just fine.
			try {

				// Call the main chunk. This will start executing the Lua program
				// in this thread, and will return when the chunk has been completely
				// executed.
				chunk.call();

				// After this point, we can call the main method since the chunk was
				// just called, and all the methods in said chunk have been declared.

				// get our main function
				LuaValue value = globals.get("main");

				// set the exit function
				exit = globals.get("exit");

				// if the main function exists, call it.
				//
				// some programs won't have a main method. That's fine, in that case
				// most of the code will be in the chunk itself.
				if (value.isfunction()) {
					value.call(args);
				}
			}
			// if the program was interrupted by our debug/interrupt lib
			catch (ProgramInterruptException ex) {
				print("\nProgram terminated");
			}
			// if we encountered an error, we go through quite the process to handle it
			catch (LuaError err) {
				handleLuaError(err);
			}
			// regardless if we encountered an error or not, we try to call our exit function.
			finally {
				// if the exit function exists, and our program has not been terminated
				if (exit != null && exit.isfunction() && !terminated()) {

					try {

						// call the function
						exit.call();

					}
					// again, if the exit function was interrupted.
					catch (ProgramInterruptException ex) {
						print("\nExit routine terminated");
					}
					// if there was an error, handle it the same way.
					catch (LuaError err) {
						handleLuaError(err);
					}
				}
			}
		}
		// cleanup code
		finally {

			// unregister our pool
			if (pool != null)
				pool.cleanup();

			// clear all frame references (from the Lua graphics API)
			framePool.clear();

			// remove all registered channels (from the Lua networking API)
			registeredChannels.forEach(computer::unregisterMessageListener);
			registeredChannels.clear();

			// remove terminal hooks
			Terminal terminal = contextTerminal;
			if (terminal != null) {
				// user input handling
				terminal.setHandlerInterrupt(null);
				// hook to remove termination from users other than the owner
				terminal.setIgnoreUnauthorizedSigterm(false);
			}

			// remove all components that were set to any screen session(s)
			for (int i : allocatedSessions) {
				computer.setComponent(i, null);
			}
		}

		// at the end of this method, in one way or another, our Lua program will have ended.
	}

	private InputStream dummyInputStream() {
		return new InputStream() {
			@Override
			public int read() throws IOException {
				return 0;
			}
		};
	}

	private PrintStream dummyPrintStream() {
		return new PrintStream(new OutputStream() {
			@Override
			public void write(int b) throws IOException {}
		}) {
			@Override
			public void println(String x) {}

			@Override
			public void println(Object x) {}
		};
	}

	// finds a new id for a frame that is not taken
	protected int findFrameId() {
		int i = 0;
		while(framePool.containsKey(i))
			i++;
		return i;
	}
	public Computer getComputer() {
		return computer;
	}
	private void handleLuaError(LuaError err) {

		// print stack trace in console if in debug mode
		if (Consoles.debug)
			Consoles.getInstance().getLogger().severe("\n" + ExceptionUtils.getFullStackTrace(err));

		// start of our error string
		String errorBreakdown = err.getMessage();

		// We check for errors that caused the top-level error we just encountered, and add their
		// information to the breakdown.
		boolean cont;
		do {
			cont = false;
			if (err.getCause() instanceof LuaError) {
				errorBreakdown += "\n\nCaused by:\n" + err.getCause().getMessage();
				cont = true;
			}
			else if (err.getCause() instanceof InvocationTargetException) {
				if (((InvocationTargetException) err.getCause()).getTargetException() != null)
					errorBreakdown += "\n\nCaused by:\n"
							+ ((InvocationTargetException) err.getCause()).getTargetException().getClass().getSimpleName()
							+ ": " + ((InvocationTargetException) err.getCause()).getTargetException().getMessage();
				if (((InvocationTargetException) err.getCause()).getTargetException() instanceof LuaError) {
					err = (LuaError) ((InvocationTargetException) err.getCause()).getTargetException();
					cont = true;
				}
			}
		}
		while (cont);

		// tell the user we encountered a runtime error
		println("lua:" + ChatColor.RED + " runtime error");

		// split into lines and color each line
		String[] arr = Arrays.asList(errorBreakdown.split("\n")).stream()
				.map(this::err)
				.toArray(String[]::new);
		// combine again
		String msg = Joiner.on('\n').join(arr);

		// if the amount of lines in the breakdown is greater than 16, dump it to a file
		if (arr.length > 16) {

			// get terminal instance
			Terminal terminal = contextTerminal;
			if (terminal != null) {

				// find file name to use. We use LuaFile because
				// it's a lot easier than using FSFile.
				LuaFile file;
				int i = 0;
				while (resolve("lua_dump" + i) != null) {
					i++;
				}

				FSFile fsfile = new TouchProgram(false).touch("lua_dump" + i, computer, contextTerminal);
				file = new LuaFile(fsfile, path, contextTerminal.getCurrentDirectory(),
						this::terminated, computer);

				// grab Lua version
				String version;
				try {
					version = globals.get("_VERSION").checkjstring();
				}
				catch (LuaError ignored) {
					version = "?";
				}

				// prefix and transform message
				msg = "Lua stack trace from " + format.format(new Date(System.currentTimeMillis())) + "\n"
						+ "Lua version: " + version + "\n\n"
						+ ChatColor.stripColor(msg.replace("\t", "    "));

				// write the data to the file
				file.write(msg);

				// tell the user we dumped the error breakdown to a file
				String cd = terminal.getCurrentDirectory();
				if (cd.endsWith("/"))
					cd = cd.substring(0, cd.length() - 1);
				println("lua:" + ChatColor.RED + " stack trace too large!");
				print("lua:" + ChatColor.RED + " dumped: " + ChatColor.YELLOW + cd + "/" + "lua_dump" + i);
			}
		}
		// if small enough, print the error directly in the terminal
		else
			print(msg);
	}
	private String warning(String str) {
		return "\t" + ChatColor.YELLOW + str;
	}
	private String err(String str) {
		return "\t" + ChatColor.RED + str;
	}
	protected void print(String formatted) {
		try {
			out.write(formatted.getBytes(CHARSET));
		}
		catch (IOException e) {
			throw new LuaError(e);
		}
	}
	protected void println(String formatted) {
		print(formatted + '\n');
	}
	protected void nextLine() {
		print("\n");
	}
	protected FSBlock resolve(String input) {
		return computer.getBlock(input, contextTerminal.getCurrentDirectory());
	}
	protected void terminate() {
		terminator.run();
	}
	protected boolean terminated() {
		return terminated.getAsBoolean();
	}
	protected void delay(long ms) {
		if (restricted) ProgramUtils.sleep(ms);
	}
	public void resetInterrupt() {
		interruptLib.update();
	}
}
