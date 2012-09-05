package com.dynamo.bob.pipeline;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.IResource;
import com.dynamo.bob.Task;
import com.dynamo.bob.Task.TaskBuilder;
import com.dynamo.cr.resource.archive.ArchiveBuilder;

/**
 * Game project and disk archive builder.
 * @author chmu
 *
 */
@BuilderParams(name = "GameProjectBuilder", inExts = ".project", outExt = "", createOrder = 1000)
public class GameProjectBuilder extends Builder<Void> {

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        if (project.option("build_disk_archive", "false").equals("true")) {
            TaskBuilder<Void> builder = Task.<Void>newBuilder(this)
                    .setName(params.name())
                    .addInput(input)
                    .addOutput(input.changeExt(".projectc"))
                    .addOutput(input.changeExt(".arc"));

            for (Task<?> task : project.getTasks()) {
                for (IResource output : task.getOutputs()) {
                    builder.addInput(output);
                }
            }

            return builder.build();
        }

        return null;
    }

    private File createArchive(Task<Void> task) throws IOException {
        RandomAccessFile outFile = null;
        File tempArchiveFile = File.createTempFile("tmp", "arc");
        tempArchiveFile.deleteOnExit();
        outFile = new RandomAccessFile(tempArchiveFile, "rw");
        outFile.setLength(0);

        String root = FilenameUtils.concat(project.getRootDirectory(), project.getBuildDirectory());
        ArchiveBuilder ab = new ArchiveBuilder(root);
        int i = 0;
        for (IResource input : task.getInputs()) {
            if (i > 0) {
                // First input is game.project
                ab.add(input.getAbsPath());
            }
            ++i;
        }

        ab.write(outFile);
        outFile.close();
        return tempArchiveFile;
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError, IOException {
        FileInputStream is = null;
        try {
            File archiveFile = createArchive(task);
            is = new FileInputStream(archiveFile);
            IResource arcOut = task.getOutputs().get(1);
            arcOut.setContent(is);
            archiveFile.delete();

            IResource in = task.getInputs().get(0);
            IResource projOut = task.getOutputs().get(0);
            projOut.setContent(in.getContent());

        } finally {
            IOUtils.closeQuietly(is);
        }
    }
}

