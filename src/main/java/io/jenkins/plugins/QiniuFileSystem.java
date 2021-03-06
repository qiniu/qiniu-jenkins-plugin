package io.jenkins.plugins;

import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.Auth;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Restricted(NoExternalUse.class)
class QiniuFileSystem implements Serializable {
    private static final Logger LOG = Logger.getLogger(QiniuFileSystem.class.getName());

    @Nonnull
    private QiniuConfig config;
    @Nullable
    private Path objectNamePrefix;
    @Nonnull
    private DirectoryNode rootNode;
    @Nullable
    private IOException ioException;

    QiniuFileSystem(@Nonnull final QiniuConfig config, @Nonnull final Path objectNamePrefix) {
        this.config = config;
        this.objectNamePrefix = objectNamePrefix;
        this.rootNode = new DirectoryNode("", this, null);
        initNodes();
    }

    QiniuFileSystem(@Nonnull final QiniuConfig config, @Nonnull final IOException ioException) {
        this.config = config;
        this.objectNamePrefix = null;
        this.rootNode = new DirectoryNode("", this, null);
        this.ioException = ioException;
    }

    @Nonnull
    static QiniuFileSystem create(@Nonnull final QiniuConfig config, @Nonnull final String objectNamePrefix) {
        try {
            return new QiniuFileSystem(config, toPath(objectNamePrefix));
        } catch (InvalidPathError e) {
            return new QiniuFileSystem(config, e);
        }
    }

    private void initNodes() {
        LOG.log(Level.INFO, "QiniuFileSystem::{0}::list()", this.objectNamePrefix);
        final BucketManager bucketManager = this.config.getBucketManager();
        String marker = null;
        String prefix = this.objectNamePrefix.toString();
        if (!prefix.endsWith(File.separator)) {
            prefix += File.separator;
        }
        try {
            for (;;) {
                LOG.log(Level.INFO, "QiniuFileSystem::{0}::list(), prefix={1}, marker={2}",
                        new Object[]{this.objectNamePrefix, prefix, marker});
                final FileListing list = bucketManager.listFiles(this.config.getBucketName(), prefix, marker, 1000, null);
                if (list.items != null) {
                    for (FileInfo metadata : list.items) {
                        final Path path = this.objectNamePrefix.relativize(toPath(metadata.key));
                        LOG.log(Level.INFO, "QiniuFileSystem::{0}::list(), key={1}",
                                new Object[]{this.objectNamePrefix, path.toString()});
                        this.createFileNodeByPath(path, metadata);
                    }
                }
                marker = list.marker;
                if (marker == null || marker.isEmpty()) {
                    break;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.INFO, "QiniuFileSystem::{0}::list() error: {1}", new Object[]{this.objectNamePrefix, e});
            this.ioException = e;
        }
        LOG.log(Level.INFO, "QiniuFileSystem::{0}::list() done", this.objectNamePrefix);
    }

    @CheckForNull
    Node getNodeByPath(@Nonnull Path path, boolean createDirectory, boolean createNodeAsDirectory) throws InvalidPathError {
        DirectoryNode currentNode = this.rootNode;
        for (int i = 0; i < path.getNameCount(); i++) {
            final String currentNodeName = path.getName(i).toString();
            Node newCurrentNode = currentNode.getByName(currentNodeName);
            if (i < path.getNameCount() - 1) {
                if (newCurrentNode != null && newCurrentNode.isDirectory()) {
                    currentNode = (DirectoryNode) newCurrentNode;
                } else if (createDirectory && newCurrentNode == null) {
                    currentNode = currentNode.addChildDirectoryNode(currentNodeName);
                    LOG.log(Level.INFO, "create directory node: {0}", currentNode.getPath().toString());
                } else {
                    throw new InvalidPathError("Path " + path.toString() + " is invalid, file " + path.getName(i).toString() + " is not directory");
                }
            } else if (createNodeAsDirectory && newCurrentNode == null) {
                currentNode = currentNode.addChildDirectoryNode(currentNodeName);
                LOG.log(Level.INFO, "create directory node: {0}", currentNode.getPath().toString());
                return currentNode;
            } else {
                return newCurrentNode;
            }
        }
        return currentNode;
    }

    @Nonnull
    FileNode getFileNodeByPath(@Nonnull Path path, boolean createDirectory) throws InvalidPathError {
        final Node node = this.getNodeByPath(path, createDirectory, false);
        if (node != null && node.isFile()) {
            return (FileNode) node;
        } else {
            throw new InvalidPathError("Path " + path.toString() + " is invalid, file " + path.getFileName() + " is not a file");
        }
    }

    @Nonnull
    DirectoryNode getDirectoryNodeByPath(@Nonnull Path path, boolean createDirectory) throws InvalidPathError {
        final Node node = this.getNodeByPath(path, createDirectory, createDirectory);
        if (node != null && node.isDirectory()) {
            return (DirectoryNode) node;
        } else {
            throw new InvalidPathError("Path " + path.toString() + " is invalid, file " + path.getFileName() + " is not a directory");
        }
    }

    @Nonnull
    DirectoryNode getParentNodeByPath(@Nonnull Path path, boolean createDirectory) throws InvalidPathError {
        Path parentPath = path.getParent();
        if (parentPath != null) {
            return this.getDirectoryNodeByPath(parentPath, createDirectory);
        } else {
            return this.rootNode;
        }
    }

    void createFileNodeByPath(@Nonnull Path path, @Nonnull FileInfo metadata) throws InvalidPathError {
        final Path childPath = path.getFileName();
        if (childPath != null) {
            final DirectoryNode parentNode = this.getParentNodeByPath(path, true);
            parentNode.addChildFileNode(childPath.toString(), metadata);
            LOG.log(Level.INFO, "create file node: {0}", path.toString());
        } else {
            throw new InvalidPathError("path must not be empty");
        }
    }

    void deleteFileNodeByPath(@Nonnull Path path) throws InvalidPathError, DeleteDirectoryError {
        LOG.log(Level.INFO, "delete file node: {0}", path.toString());
        DirectoryNode parentNode = this.getParentNodeByPath(path, false);
        final Path childPath = path.getFileName();
        if (childPath == null) {
            throw new InvalidPathError("path must not be empty");
        }
        final String childName = childPath.toString();
        final Node subNode = parentNode.getByName(childName);
        if (subNode != null && subNode.isFile()) {
            parentNode.removeChildNode(childName);
            while (parentNode.isEmpty()) {
                final String name = parentNode.getNodeName();
                parentNode = parentNode.getParentNode();
                if (parentNode == null) {
                    return;
                }
                parentNode.removeChildNode(name);
            }
        } else {
            throw new DeleteDirectoryError("Path " + path.toString() + " is not a file");
        }
    }

    void deleteAll() throws IOException {
        LOG.log(Level.INFO, "delete all nodes");
        final BucketManager bucketManager = this.config.getBucketManager();
        final BucketManager.BatchOperations batch = new BucketManager.BatchOperations();
        int counter = 0;
        String marker = null;
        String prefix = "";
        if (this.objectNamePrefix != null) {
            prefix = this.objectNamePrefix.toString();
        }

        if (!prefix.endsWith(File.separator)) {
            prefix += File.separator;
        }

        for (;;) {
            final FileListing list = bucketManager.listFiles(this.config.getBucketName(), prefix, marker, 1000, null);
            if (list.items != null) {
                for (FileInfo metadata : list.items) {
                    batch.addDeleteOp(this.config.getBucketName(), metadata.key);
                    counter++;
                    if (counter >= 1000) {
                        bucketManager.batch(batch);
                        batch.clearOps();
                        counter = 0;
                    }
                }
            }
            marker = list.marker;
            if (marker == null || marker.isEmpty()) {
                break;
            }
        }
        if (counter > 0) {
            bucketManager.batch(batch);
        }
        this.rootNode.childrenNodes.clear();
    }

    void mayThrowIOException() throws IOException {
        if (this.ioException != null) {
            throw this.ioException;
        }
    }

    static abstract class Node {
        @Nonnull
        final QiniuFileSystem fileSystem;
        @Nullable
        final DirectoryNode parentNode;
        @Nonnull
        final String nodeName;

        Node(@Nonnull final String nodeName, @Nonnull final QiniuFileSystem fileSystem, @Nullable final DirectoryNode parentNode) {
            this.nodeName = nodeName;
            this.fileSystem = fileSystem;
            this.parentNode = parentNode;
        }

        @Nonnull
        String getNodeName() {
            return this.nodeName;
        }

        @Nonnull
        QiniuFileSystem getFileSystem() {
            return this.fileSystem;
        }

        @CheckForNull
        DirectoryNode getParentNode() {
            return this.parentNode;
        }

        abstract boolean isFile();
        abstract boolean isDirectory();

        @Nonnull
        Path getPath() {
            final Node parentNode = this.getParentNode();
            if (parentNode != null) {
                return parentNode.getPath().resolve(this.nodeName);
            } else {
                return FileSystems.getDefault().getPath(this.nodeName);
            }
        }
    }

    static final class DirectoryNode extends Node {
        @Nonnull
        private final Map<String, Node> childrenNodes;

        DirectoryNode(@Nonnull final String nodeName, @Nonnull final QiniuFileSystem fileSystem, @Nullable final DirectoryNode parentNode) {
            super(nodeName, fileSystem, parentNode);
            this.childrenNodes = new HashMap<>();
        }

        @Nonnull
        DirectoryNode addChildDirectoryNode(@Nonnull final String name) {
            final DirectoryNode childNode = new DirectoryNode(name, this.fileSystem, this);
            this.childrenNodes.put(name, childNode);
            return childNode;
        }

        @Nonnull
        FileNode addChildFileNode(@Nonnull final String name, @Nonnull final FileInfo metadata) {
            final FileNode childNode = new FileNode(name, metadata, this.fileSystem, this);
            this.childrenNodes.put(name, childNode);
            return childNode;
        }

        void removeChildNode(@Nonnull final String name) {
            this.childrenNodes.remove(name);
        }

        @CheckForNull
        Node getByName(@Nonnull final String name) {
            return this.childrenNodes.get(name);
        }

        boolean isEmpty() {
            return this.childrenNodes.isEmpty();
        }

        @Nonnull
        Collection<Node> getChildrenNodes() {
            return this.childrenNodes.values();
        }

        @Nonnull
        int getChildrenCount() {
            return this.childrenNodes.size();
        }

        @Override
        boolean isFile() {
            return false;
        }

        @Override
        boolean isDirectory() {
            return true;
        }
    }

    public static final class InvalidPathError extends FileNotFoundException {
        InvalidPathError(String detail) {
            super(detail);
        }
    }

    public static final class DeleteDirectoryError extends IOException {
        DeleteDirectoryError(String detail) {
            super(detail);
        }
    }

    static final class FileNode extends Node {
        private final FileInfo metadata;

        FileNode(@Nonnull final String nodeName, @Nonnull final FileInfo metadata,
                 @Nonnull final QiniuFileSystem fileSystem, @Nonnull final DirectoryNode parentNode) {
            super(nodeName, fileSystem, parentNode);
            this.metadata = metadata;
        }

        @Nonnull
        public FileInfo getMetadata() {
            return this.metadata;
        }

        @Override
        boolean isFile() {
            return true;
        }

        @Override
        boolean isDirectory() {
            return false;
        }
    }

    @Nonnull
    static Path toPath(@Nonnull final String objectName) throws InvalidPathError {
        final String[] segments = objectName.split(Pattern.quote(File.separator));
        switch (segments.length) {
        case 0:
            throw new InvalidPathError("Path is empty");
        case 1:
            return FileSystems.getDefault().getPath(segments[0]);
        default:
            return FileSystems.getDefault().getPath(segments[0], Arrays.copyOfRange(segments, 1, segments.length));
        }
    }

    @Nonnull
    public QiniuConfig getConfig() {
        return this.config;
    }

    @CheckForNull
    public Path getObjectNamePrefix() {
        return this.objectNamePrefix;
    }

    @Nonnull
    public DirectoryNode getRootNode() {
        return this.rootNode;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeObject(this.config);
        SerializeUtils.serializePath(out, this.objectNamePrefix);
        if (this.ioException != null) {
            out.writeBoolean(true);
            out.writeObject(this.ioException.getMessage());
        } else {
            out.writeBoolean(false);
        }
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        this.config = (QiniuConfig) in.readObject();
        this.objectNamePrefix = SerializeUtils.deserializePath(in);
        if (in.readBoolean()) {
            this.ioException = new IOException((String) in.readObject());
        } else {
            this.ioException = null;
        }
        this.rootNode = new DirectoryNode("", this, null);
        initNodes();
    }
}
