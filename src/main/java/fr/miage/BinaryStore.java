package fr.miage.filestore;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;

public class BinaryStore implements BinaryStoreService {

	HashMap<String, InputStream> mapUrl = new HashMap<>();

	@Override
	public boolean exists(String key) throws BinaryStoreServiceException {
		// TODO Auto-generated method stub
		return mapUrl.containsKey(key);
	}

	@Override
	public String put(InputStream is) throws BinaryStoreServiceException {
		// TODO Auto-generated method stub
		String key = generateKey();
		mapUrl.put(key, is);
		return key;
	}

	public String generateKey() {
		Date date = new Date();
		String result = "";
		try {
			MessageDigest messageDigest;
			messageDigest = MessageDigest.getInstance("MD5");
			messageDigest.reset();
			messageDigest.update(date.toString().getBytes(Charset.forName("UTF8")));
			final byte[] resultByte = messageDigest.digest();
			result = new String(resultByte);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

		return result;

	}

	@Override
	public InputStream get(String key) throws BinaryStoreServiceException, BinaryStreamNotFoundException {
		// TODO Auto-generated method stub
		InputStream data;
		data = mapUrl.get(key);
		if (data == null) {
			throw new BinaryStreamNotFoundException("La clé n'existe pas. Veuillez entrer une autre clé.");
		}
		else
		{
			return data;	
		}
	}

}
