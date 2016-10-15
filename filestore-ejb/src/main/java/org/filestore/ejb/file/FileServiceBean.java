package org.filestore.ejb.file;

import org.filestore.ejb.config.FileStoreConfig;
import org.filestore.ejb.file.entity.FileItem;
import org.filestore.ejb.file.metrics.FileServiceMetricsBean;
import org.filestore.ejb.store.BinaryStoreService;
import org.filestore.ejb.store.BinaryStoreServiceException;
import org.filestore.ejb.store.BinaryStreamNotFoundException;

import javax.annotation.Resource;
import javax.ejb.*;
import javax.interceptor.Interceptors;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.*;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.Thread.sleep;

@Stateless(name = "fileservice")
@Local(FileService.class)
@Interceptors(FileServiceMetricsBean.class)
public class FileServiceBean implements FileService {

    private static final Logger LOGGER = Logger.getLogger(FileServiceBean.class.getName());

    @PersistenceContext(unitName="filestore-pu")
    protected EntityManager em;
    @Resource
    protected SessionContext ctx;
    @EJB
    protected BinaryStoreService store;

    @Resource(name = "java:jboss/mail/Default")
    private Session session;

    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public String postFile(String owner, List<String> receivers, String message, String name, byte[] data) throws FileServiceException {
        LOGGER.log(Level.INFO, "Post File called (byte[])");
        String id = this.internalPostFile(owner, receivers, message, name, new ByteArrayInputStream(data));
        return id;
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public String postFile(String owner, List<String> receivers, String message, String name, InputStream stream) throws FileServiceException {
        LOGGER.log(Level.INFO, "Post File called (InputStream)");
        String id = this.internalPostFile(owner, receivers, message, name, stream);
        return id;
    }

    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    private String internalPostFile(String owner, List<String> receivers, String message, String name, InputStream stream) throws FileServiceException {
        try {
            String streamid = store.put(stream);
            String id = UUID.randomUUID().toString().replaceAll("-", "");
            FileItem file = new FileItem();
            file.setId(id);
            file.setOwner(owner);
            file.setReceivers(receivers);
            file.setMessage(message);
            file.setName(name);
            file.setStream(streamid);
            em.persist(file);

            notifyOwner(owner, id);
            int i = 0;
            for ( String receiver : receivers ) {
                if(i != 10) {
                    notifyReceiver(receiver, id, message);
                    i++;
                }
                else{
                    sleep(1000);
                    i= 0;
                }
            }

            return id;
        } catch ( BinaryStoreServiceException e ) {
            LOGGER.log(Level.SEVERE, "An error occured during storing binary content", e);
            ctx.setRollbackOnly();
            throw new FileServiceException("An error occured during storing binary content", e);
        } catch ( Exception e ) {
            LOGGER.log(Level.SEVERE, "unexpected error during posting file", e);
            throw new FileServiceException(e);
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public FileItem getFile(String id) throws FileServiceException {
        LOGGER.log(Level.INFO, "Get File called");
        try {
            FileItem item = em.find(FileItem.class, id);
            if ( item == null ) {
                throw new FileServiceException("Unable to get file with id '" + id + "' : file does not exists");
            }
            return item;
        } catch ( Exception e ) {
            LOGGER.log(Level.SEVERE, "An error occured during getting file", e);
            throw new FileServiceException(e);
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public InputStream getFileContent(String id) throws FileServiceException {
        LOGGER.log(Level.INFO, "Get File Content called");
        return this.internalGetFileContent(id);
    }


    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public byte[] getWholeFileContent(String id) throws FileServiceException {
        LOGGER.log(Level.INFO, "Get Whole File Content called");
        InputStream is = this.internalGetFileContent(id);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[1024];
            int len = 0;
            while ( (len=is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            throw new FileServiceException("unable to copy stream", e);
        } finally {
            try {
                baos.flush();
                baos.close();
                is.close();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "error during closing streams", e);
            }
        }
        return baos.toByteArray();
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    private InputStream internalGetFileContent(String id) throws FileServiceException {
        try {
            FileItem item = em.find(FileItem.class, id);
            if ( item == null ) {
                throw new FileServiceException("Unable to get file with id '" + id + "' : file does not exists");
            }
            InputStream is = store.get(item.getStream());
            return is;
        } catch ( BinaryStreamNotFoundException e ) {
            LOGGER.log(Level.SEVERE, "No binary content found for this file item !!", e);
            throw new FileServiceException("No binary content found for this file item !!", e);
        } catch ( BinaryStoreServiceException e ) {
            LOGGER.log(Level.SEVERE, "An error occured during reading binary content", e);
            throw new FileServiceException("An error occured during reading binary content", e);
        } catch ( Exception e ) {
            LOGGER.log(Level.SEVERE, "unexpected error during getting file", e);
            throw new FileServiceException(e);
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void deleteFile(String id) throws FileServiceException {
        LOGGER.log(Level.INFO, "Delete File called");
        try {
            FileItem item = em.find(FileItem.class, id);
            if ( item == null ) {
                throw new FileServiceException("Unable to delete file with id '" + id + "' : file does not exists");
            }
            em.remove(item);
            try {
                store.delete(item.getStream());
            } catch ( BinaryStreamNotFoundException | BinaryStoreServiceException e ) {
                LOGGER.log(Level.WARNING, "unable to delete binary content, may result in orphean file", e);
            }
        } catch ( Exception e ) {
            LOGGER.log(Level.SEVERE, "unexpected error during deleting file", e);
            ctx.setRollbackOnly();
            throw new FileServiceException(e);
        }
    }

    private void notifyOwner(String owner, String id) throws MessagingException, UnsupportedEncodingException {
        javax.mail.Message msg = new MimeMessage(session);
        msg.setSubject("Your file has been received");
        msg.setRecipient(RecipientType.TO,new InternetAddress(owner));
        msg.setFrom(new InternetAddress("admin@filexchange.org","FileXChange"));
        msg.setContent("Hi, this mail confirm the upload of your file. The file will be accessible at url : "
                + FileStoreConfig.getDownloadBaseUrl() + id, "text/html");
        Transport.send(msg);
        try {
            sleep(5000);
        } catch (InterruptedException e) {
            //
        }
    }

    private void notifyReceiver(String receiver, String id, String message) throws MessagingException, UnsupportedEncodingException {
        javax.mail.Message msg = new MimeMessage(session);
        msg.setSubject("Notification");
        msg.setRecipient(RecipientType.TO,new InternetAddress(receiver));
        msg.setFrom(new InternetAddress("admin@filexchange.org","FileXChange"));
        msg.setContent("Hi, a file has been uploaded for you and is accessible at url : <br/><br/>"
                + FileStoreConfig.getDownloadBaseUrl() + id + "<br/><br/>"
                + "The sender lets you a message :<br/><br/>" + message, "text/html");
        Transport.send(msg);
        try {
            sleep(5000);
        } catch (InterruptedException e) {
            //
        }
    }

}