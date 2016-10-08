package org.filestore.ejb.file;

/**
 * Created by Alexandre on 08/10/2016.
 */
public interface FileServiceMetrics {

    public int getTotalUploads() throws FileServiceException;

    public int getTotalDownloads() throws FileServiceException;

    public int getUptime() throws FileServiceException;

}


