/*
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2015, 2016 
*/
package quarks.connectors.file;

import java.io.File;
import java.nio.file.WatchService;
import java.util.Comparator;

import quarks.connectors.file.runtime.DirectoryWatcher;
import quarks.connectors.file.runtime.IFileWriterPolicy;
import quarks.connectors.file.runtime.TextFileReader;
import quarks.connectors.file.runtime.TextFileWriter;
import quarks.function.BiFunction;
import quarks.function.Function;
import quarks.function.Supplier;
import quarks.topology.TSink;
import quarks.topology.TStream;
import quarks.topology.TopologyElement;

/**
 * {@code FileStreams} is a connector for integrating with file system objects.
 * <p>
 * File stream operations include:
 * <ul>
 * <li>Write tuples to text files - {@link #textFileWriter(TStream, Supplier, Supplier) textFileWriter}</li>
 * <li>Watch a directory for new files - {@link #directoryWatcher(TopologyElement, Supplier) directoryWatcher}</li>
 * <li>Create tuples from text files - {@link #textFileReader(TStream, Function, BiFunction) textFileReader}</li>
 * </ul>
 */
public class FileStreams {
    @SuppressWarnings("unused")
    private static final FileStreams forCodeCoverage = new FileStreams();
    private FileStreams() {};
    
    /**
     * Declare a stream containing the absolute pathname of 
     * newly created file names from watching {@code directory}.
     * <p>
     * This is the same as {@code directoryWatcher(t, () -> dir, null)}.
     * 
     * @param directory
     *            Name of the directory to watch.
     * @return Stream containing absolute pathnames of newly created files in
     *            {@code directory}.
     */
    public static TStream<String> directoryWatcher(TopologyElement te,
            Supplier<String> directory) {
        return directoryWatcher(te, directory, null);
    }
    
    /**
     * Declare a stream containing the absolute pathname of 
     * newly created file names from watching {@code directory}.
     * <p>
     * Hidden files (java.io.File.isHidden()==true) are ignored.
     * This is compatible with {@code textFileWriter}.
     * <p>
     * Sample use:
     * <pre>{@code
     * String dir = "/some/directory/path";
     * Topology t = ...
     * TStream<String> pathnames = FileStreams.directoryWatcher(t, () -> dir, null);
     * }</pre>
     * <p>
     * The order of the files in the stream is dictated by a {@link Comparator}.
     * The default comparator orders files by {@link File#lastModified()} values.
     * There are no guarantees on the processing order of files that
     * have the same lastModified value.
     * Note, lastModified values are subject to filesystem timestamp
     * quantization - e.g., 1second.
     * <p>
     * Note: due to the asynchronous nature of things, if files in the
     * directory may be removed, the receiver of a tuple with a "new" file
     * pathname may need to be prepared for the pathname to no longer be
     * valid when it receives the tuple or during its processing of the tuple.
     * <p>
     * The behavior on MacOS may be unsavory, even as recent as Java8, as
     * MacOs Java lacks a native implementation of {@link WatchService}.
     * The result can be a delay in detecting newly created files (e.g., 10sec)
     * as well not detecting rapid deletion and recreation of a file.
     * 
     * @param directory
     *            Name of the directory to watch.
     * @param comparator
     *            Comparator to use to order newly seen file pathnames.
     *            May be null.
     * @return Stream containing absolute pathnames of newly created files in
     *            {@code directory}.
     */
    public static TStream<String> directoryWatcher(TopologyElement te,
            Supplier<String> directory, Comparator<File> comparator) {
        return te.topology().source(() -> new DirectoryWatcher(directory, comparator));
    }
    
    /**
     * Declare a stream containing the lines read from the files
     * whose pathnames correspond to each tuple on the {@code pathnames}
     * stream.
     * <p>
     * This is the same as {@code textFileReader(pathnames, null, null)}
     * <p>
     * Sample use:
     * <pre>{@code
     * String dir = "/some/directory/path";
     * Topology t = ...
     * TStream<String> pathnames = FileStreams.directoryWatcher(t, () -> dir);
     * TStream<String> contents = FileStreams.textFileReader(pathnames);
     * contents.print();
     * }</pre>
     * 
     * @param pathnames
     *            Stream containing pathnames of files to read.
     * @return Stream containing lines from the files.
     */
    public static TStream<String> textFileReader(TStream<String> pathnames) {
        return textFileReader(pathnames, null, null);
    }

    /**
     * Declare a stream containing the lines read from the files
     * whose pathnames correspond to each tuple on the {@code pathnames}
     * stream.
     * <p>
     * All files are assumed to be encoded in UTF-8.  The lines are
     * output in the order they appear in each file, with the first line of
     * a file appearing first.  A file is not subsequently monitored for
     * additional lines.
     * <p>
     * If a file can not be read, e.g., a file doesn't exist at that pathname
     * or the pathname is for a directory,
     * an error will be logged.
     * <p>
     * Optional {@code preFn} and {@code postFn} functions may be supplied.
     * These are called prior to processing a tuple (pathname) and after
     * respectively.  They provide a way to encode markers in the generated
     * stream.
     * <p>
     * Sample use:
     * <pre>{@code
     * // watch a directory for files, creating a stream with the contents of
     * // each file.  Use a preFn to include a file separator marker in the
     * // stream. Use a postFn to delete a file once it's been processed.
     * String dir = "/some/directory/path";
     * Topology t = ...
     * TStream<String> pathnames = FileStreams.directoryWatcher(t, () -> dir);
     * TStream<String> contents = FileStreams.textFileReader(
     *              pathnames,
     *              path -> { return "###<PATH-MARKER>### " + path },
     *              (path,exception) -> { new File(path).delete(), return null; }
     *              );
     * contents.print();
     * }</pre>
     * 
     * @param pathnames
     *            Stream containing pathnames of files to read.
     * @param preFn
     *            Pre-visit {@code Function<String,String>}.
     *            The input is the pathname.  
     *            The result, when non-null, is added to the output stream.
     *            The function may be null.
     * @param postFn
     *            Post-visit {@code BiFunction<String,Exception,String>}.
     *            The input is the pathname and an exception.  The exception
     *            is null if there were no errors.
     *            The result, when non-null, is added to the output stream.
     *            The function may be null.
     * @return Stream containing lines from the files.
     */
    public static TStream<String> textFileReader(TStream<String> pathnames,
        Function<String,String> preFn, BiFunction<String,Exception,String> postFn) {
        
        TextFileReader reader = new TextFileReader();
        reader.setPre(preFn);
        reader.setPost(postFn);
        return pathnames.pipe(reader);
    }
    
    /**
     * Write the contents of a stream to files.
     * <p>
     * The default {@link FileWriterPolicy} is used.
     * <p>
     * This is the same as {@code textFileWriter(contents, basePathname, null)}.
     * <p>
     * Sample use:
     * <pre>{@code
     * // write a stream of LogEvent to files, using the default
     * // file writer policy
     * String basePathname = "/myLogDir/LOG"; // yield LOG_YYYYMMDD_HHMMSS
     * TStream<MyLogEvent> events = ...
     * TStream<String> stringEvents = events.map(event -> event.toString()); 
     * FileStreams.textFileWriter(stringEvents, () -> basePathname);
     * }</pre>
     * @param contents the lines to write
     * @param basePathname the base pathname of the created files
     */
    public static TSink<String> textFileWriter(TStream<String> contents,
            Supplier<String> basePathname) {
        return textFileWriter(contents, basePathname, null);
    }
    
    /**
     * Write the contents of a stream to files subject to the control
     * of a file writer policy.
     * <p>
     * A separate policy instance must be used for invocation.
     * A default {@link FileWriterPolicy} is used if a policy is not specified.
     * <p>
     * Sample use:
     * <pre>{@code
     * // write a stream of LogEvent to files using a policy of:
     * // no additional flush, 100 events per file, retain 5 files
     * IFileWriterPolicy<String> policy = new FileWriterPolicy<String>(
     *           FileWriterFlushConfig.newImplicitConfig(),
     *           FileWriterCycleConfig.newCountBasedConfig(100),
     *           FileWriterRetentionConfig.newFileCountBasedConfig(5)
     *           );
     * String basePathname = "/myLogDir/LOG"; // yield LOG_YYYYMMDD_HHMMSS
     * TStream<MyLogEvent> events = ...
     * TStream<String> stringEvents = events.map(event -> event.toString()); 
     * FileStreams.textFileWriter(stringEvents, () -> basePathname, () -> policy);
     * }</pre>
     * @param contents the lines to write
     * @param basePathname the base pathname of the created files
     * @param policy the policy to use.  may be null.
     * @see FileWriterPolicy
     */
    public static TSink<String> textFileWriter(TStream<String> contents,
            Supplier<String> basePathname, Supplier<IFileWriterPolicy<String>> policy) {
        if (policy == null) {
            IFileWriterPolicy<String> defaultPolicy = new FileWriterPolicy<String>(){};
            policy = () -> defaultPolicy;
        }
        return contents.sink(new TextFileWriter(basePathname, policy));
    }
}
