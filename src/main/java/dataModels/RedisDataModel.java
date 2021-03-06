package dataModels;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import modelInterfaces.*;

import redis.clients.jedis.Client;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import rest_Models.DefaultApplication;
import rest_Models.DefaultUser;

//******************************REDIS DATA LAYER********************************
/*Don't forget that as redis is a cache we have to keep track of the oldest element in the system.
 /*Other options are using the redis TTL or even keeping track of the hits each id has.
 * 
 * Time is limited so we'll do the timestamp one first, given the time the right option would be a breed 
 * between the timestamp and the number of hits per id, that way we could have a newer element with 1000 hits
 * and pick the newer id comparing to an older with 1001 hits.
 * 
 * TLDR: Do ratio between hits and timestamp.
 * 
 * Present solution:
 * Sorted set using a UNIX timestamp as score.
 * 
 */
public class RedisDataModel implements CacheInterface {

	// request types
	private static final String AUDIO = "audio";
	private static final String IMAGES = "images";
	private static final String VIDEO = "video";
	private static final String STORAGE = "storage";
	private JedisPool pool = new JedisPool(new JedisPoolConfig(), server);
	Jedis jedis;
	private final static String server = "localhost";

	private static final int RedisCachePORT = 6379;

	public RedisDataModel() {
		jedis = new Jedis(server, RedisCachePORT);
	}

	public long getCacheSize() {
		String info = jedis.info();
		String[] array = info.split("\r\n");
		long size = 0;
		for (int i = 0; i < array.length; i++) {
			if (array[i].contains("used_memory")) {
				String[] split = array[i].split(":");
				if (split[0].equalsIgnoreCase("used_memory"))
					size = Long.parseLong(split[1]);
			}
		}
		return size;
	}

	public Object clone() throws CloneNotSupportedException {
		throw new CloneNotSupportedException();
	}

	/**
	 * Keep it safe, this method should not be accessible.
	 */
	public Set<String> getAllUserIdsForApp(String appId) {
		Jedis jedis = pool.getResource();
		Set<String> usersId;
		try{
			usersId = jedis.smembers("app:" + appId + ":users");
		}finally {
			pool.returnResource(jedis);
		}
		return usersId;
	}

	public Set<String> getAllAppIds() {
		Jedis jedis = pool.getResource();
		Set<String> result;
		try {
			Set<String> allApps = jedis.keys("apps:");
			Set<String> inactiveApps = jedis.smembers("apps:inactive");
			result = new HashSet<String>(allApps.size());
			Iterator<String> i = allApps.iterator();
			while (i.hasNext()) {
				String element = i.next();
				if (!inactiveApps.contains(element))
					result.add(element);
			}
		} finally {
			pool.returnResource(jedis);
		}
		return result;
	}

	/**
	 * Return codes 1 = Action performed -1 = App does not exist 0 = No action
	 * was performed
	 * 
	 */
	public boolean deleteApp(String appId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (jedis.exists("apps:" + appId)) {
				long unixTime = System.currentTimeMillis() / 1000L;
				jedis.zrem("apps:time", appId);
				Set<String> inactiveApps = jedis.smembers("apps:inactive");
				Iterator<String> it = inactiveApps.iterator();
				boolean inactive = false;
				while (it.hasNext() && !inactive) {
					if (it.next().equals(appId))
						inactive = true;
				}
				if (!inactive) {
					jedis.hset("apps:" + appId, "alive", "false");
					jedis.sadd("apps:inactive", appId);
					sucess = true;
				}
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	/**
	 * Return codes 1 = Updated application successfully; -1 = Application with
	 * currentId does not exist.
	 * 
	 * @return
	 */
	public boolean updateApp(String currentId, String newId, String alive) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (jedis.exists("apps:" + currentId)) {
				jedis.zrem("apps:time", currentId);
				long unixTime = System.currentTimeMillis() / 1000L;
				jedis.zadd("apps:time", unixTime, newId);
				Map<String, String> tempValues = jedis.hgetAll("apps:"
						+ currentId);
				for (Map.Entry<String, String> entry : tempValues.entrySet()) {
					jedis.hset("apps:" + newId, entry.getKey(),
							entry.getValue());
				}
				jedis.del("apps:" + currentId);
				sucess = true;
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	/**
	 * Returns the fields of the corresponding application
	 */
	public Map<String, String> getApplication(String appId) {
		Jedis jedis = pool.getResource();
		Map<String, String> appFields = null;
		try {
			if (jedis.exists("apps:" + appId)) {
				appFields = jedis.hgetAll("apps:" + appId);
			}
		} finally {
			pool.returnResource(jedis);
		}
		return appFields;
	}

	public boolean appExists(String appId) {
		Jedis jedis = pool.getResource();
		boolean op;
		try {
			op = jedis.exists("apps:" + appId);
		}finally {
			pool.returnResource(jedis);
		}
		return op;
	}

	public boolean userExistsInApp(String appId, String email) {
		Jedis jedis = pool.getResource();
		boolean userExists = false;
		try {
			Set<String> usersInApp = this.jedis.smembers("app:" + appId + ":users");
			Iterator<String> it = usersInApp.iterator();
			if (jedis.sismember("app:" + appId + ":users:emails", email))
				userExists = true;
		} finally {
			pool.returnResource(jedis);
		}
		return userExists;
	}

	public boolean createUserWithFlag(String appId, String userId, String userName,
			String email, byte[] salt, byte[] hash, String creationDate, String userFile)
					throws UnsupportedEncodingException {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (!jedis.exists("users:" + userId)) {
				long unixTime = System.currentTimeMillis() / 1000L;
				jedis.zadd("users:time", unixTime, appId + ":" + userId);
				jedis.hset("users:" + userId, "userId", userId);
				jedis.hset("users:" + userId, "userName", userName);
				jedis.hset("users:" + userId, "email", email);
				jedis.hset("users:" + userId, "salt",
						new String(salt,
								"ISO-8859-1"));
				jedis.hset(("users:" + userId), "lastActive", new Date().toString());
				jedis.hset(("users:" + userId), "userFile", userFile);
				jedis.hset(("users:" + userId), "hash",
						new String(hash, "ISO-8859-1"));
				jedis.hset("users:" + userId, "alive", new String("true"));
				jedis.hset("users:" + userId, "creationDate", creationDate);
				jedis.sadd("app:" + appId + ":users", userId);
				jedis.sadd("app:" + appId + ":users:emails", email);
				sucess = true;
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public boolean createUserWithoutFlag(String appId, String userId, String userName,
			String email, byte[] salt, byte[] hash, String creationDate)
					throws UnsupportedEncodingException {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (!jedis.exists("users:" + userId)) {
				long unixTime = System.currentTimeMillis() / 1000L;
				jedis.zadd("users:time", unixTime, appId + ":" + userId);
				jedis.hset("users:" + userId, "userId", userId);
				jedis.hset("users:" + userId, "userName", userName);
				jedis.hset("users:" + userId, "email", email);
				jedis.hset("users:" + userId, "salt",
						new String(salt,
								"ISO-8859-1"));
				jedis.hset(("users:" + userId), "lastActive", new Date().toString());
				jedis.hset(("users:" + userId), "hash",
						new String(hash, "ISO-8859-1"));
				jedis.hset("users:" + userId, "alive", new String("true"));
				jedis.hset("users:" + userId, "creationDate", creationDate);
				jedis.sadd("app:" + appId + ":users", userId);
				jedis.sadd("app:" + appId + ":users:emails", email);
				sucess = true;
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}
	
	/**
	 * Checks if user is present in the app:{appId}:users and if it is returns
	 * its fields
	 * 
	 * @param appId
	 * @param userId
	 * @return
	 */
	public Map<String, String> getUser(String appId, String userId) {
		Jedis jedis = pool.getResource();
		Map<String, String> userFields = null;
		try {
			Set<String> usersOfApp = jedis.smembers("app:" + appId + ":users");
			Iterator<String> it = usersOfApp.iterator();
			boolean userExistsforApp = false;
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(userId))
					userExistsforApp = true;
			if (userExistsforApp) {
				userFields = jedis.hgetAll("users:" + userId);
			}
		} finally {
			pool.returnResource(jedis);
		}
		return userFields;
	}

	public Map<String, String> getStorageInApp(String appId, String storageId) {
		Jedis jedis = pool.getResource();
		Map<String, String> storageFields = null;
		try {
			Set<String> storageOfApp = jedis.smembers("app:" + appId
					+ ":storage");
			Iterator<String> it = storageOfApp.iterator();
			boolean storageExists = false;
			while (it.hasNext())
				if (it.next().equals(storageId))
					storageExists = true;
			if (storageExists)
				storageFields = jedis.hgetAll("storage:" + storageId);
		} finally {
			pool.returnResource(jedis);
		}
		return storageFields;
	}

	/**
	 * If forever is true, then it deletes the user forever, if not it sets it
	 * as inactive.
	 * 
	 * @param userId
	 * @return
	 */
	public boolean deleteUser(String appId, String userId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (jedis.exists("users:"+userId)) {
				jedis.zrem("users:time", userId);
				if(jedis==this.jedis)
					System.out.println("ss");
				Client c1 = this.jedis.getClient();
				int a = c1.getPort();
				if(this.jedis.equals(this.jedis))
					System.out.println("aa");
				this.jedis.hset("users:" + userId, "alive", "false");
				this.jedis.sadd("app:" + appId + ":users:inactive", appId + ":"
						+ userId);
				sucess = true;
			} else {
				sucess = false;
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	/**
	 * Makes an inactive app turn active again.
	 * 
	 * @param alive
	 * @return
	 */
	public boolean updateAppName(String appId, String newAppName) {
		Jedis jedis = pool.getResource();
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("apps:time", unixTime, appId);
			jedis.hset("apps:" + appId, "appName", newAppName);
		} finally {
			pool.returnResource(jedis);
		}
		return true;
	}

	/**
	 * Updates the user, depending on the fields. If the only field sent by the
	 * request was alive, then only the alive field is updated.
	 * 
	 * @param appId
	 * @param userId
	 * @param email
	 * @param hash
	 * @param salt
	 * @param alive
	 */
	public void updateUser(String appId, String userId, String email,
			byte[] hash, byte[] salt, String alive) {
		Jedis jedis = pool.getResource();
		try {
			jedis.hset("users:" + userId, "email", email);
			jedis.hset(("users:" + userId).getBytes(), "salt".getBytes(), salt);
			jedis.hset(("users:" + userId).getBytes(), "hash".getBytes(), hash);
			jedis.hset("users:" + userId, "alive", alive);
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("users:time", unixTime, appId + ":" + userId);
		} finally {
			pool.returnResource(jedis);
		}
	}

	public Set<String> getAllAudioIds(String appId) {
		Jedis jedis = pool.getResource();
		Set<String> audioIds = null;
		try {
			audioIds = jedis.smembers("app:" + appId + ":audio");
		} finally {
			pool.returnResource(jedis);
		}
		return audioIds;
	}

	public boolean audioExistsInApp(String appId, String audioId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			Set<String> audioInApp = this.jedis.smembers("app:" + appId
					+ ":audio");
			Iterator<String> it = audioInApp.iterator();
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(audioId))
					sucess = true;
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public Map<String, String> getAudioInApp(String appId, String audioId) {
		Jedis jedis = pool.getResource();
		Map<String, String> audioFields = null;
		try {
			Set<String> audioInApp = jedis.smembers("app:" + appId + ":audio");
			Iterator<String> it = audioInApp.iterator();
			boolean audioExistsForApp = false;
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(audioId))
					audioExistsForApp = true;
			if (audioExistsForApp)
				audioFields = jedis.hgetAll("audio:" + audioId);
		} finally {
			pool.returnResource(jedis);
		}
		return audioFields;
	}

	public void deleteAudioInApp(String appId, String audioId) {
		Jedis jedis = pool.getResource();
		try {
			this.jedis.srem("app:" + appId + ":audio", audioId);
			this.jedis.del("audio:" + audioId);
			jedis.zrem("audio:time", appId + ":" + audioId);
		} finally {
			pool.returnResource(jedis);
		}
	}

	public boolean createAudioInApp(String appId, String audioId,
			String directory, String fileExtension, String size,
			String bitRate, String creationDate, String fileName, String location) {
		boolean sucess = false;
		Jedis jedis = pool.getResource();
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("audio:time", unixTime, appId + ":" + audioId);
			jedis.hset("audio:" + audioId, "dir", directory);
			jedis.hset("audio:" + audioId, "type", fileExtension);
			jedis.hset("audio:" + audioId, "bitRate", bitRate);
			jedis.hset("audio:" + audioId, "size", size);
			jedis.hset("audio:" + audioId, "creationDate", creationDate);
			jedis.hset("audio:" + audioId, "fileName", fileName);
			if(location != null)
				jedis.hset("audio:" + audioId, "location", location);
			jedis.sadd("app:" + appId + ":audio", audioId);
			sucess = true;
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public Set<String> getAllImageIdsInApp(String appId) {
		Jedis jedis = pool.getResource();
		Set<String> imageIds = null;
		try {
			imageIds = jedis.smembers("app:" + appId + ":images");
		} finally {
			pool.returnResource(jedis);
		}
		return imageIds;
	}

	public Set<String> getAllVideoIdsInApp(String appId) {
		Jedis jedis = pool.getResource();
		Set<String> videoIds = null;
		try {
			videoIds = jedis.smembers("app:" + appId + ":video");
		} finally {
			pool.returnResource(jedis);
		}
		return videoIds;
	}

	public boolean imageExistsInApp(String appId, String imageId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			Set<String> audioInApp = this.jedis.smembers("app:" + appId
					+ ":images");
			Iterator<String> it = audioInApp.iterator();
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(imageId)){
					sucess = true;
					break;
				}
					
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public Map<String, String> getImageInApp(String appId, String imageId) {
		Jedis jedis = pool.getResource();
		Map<String, String> imageFields = null;
		try {
			Set<String> imagesInApp = jedis
					.smembers("app:" + appId + ":images");
			Iterator<String> it = imagesInApp.iterator();
			boolean imageExistsForApp = false;
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(imageId))
					imageExistsForApp = true;
			if (imageExistsForApp)
				imageFields = jedis.hgetAll("images:" + imageId);
		} finally {
			pool.returnResource(jedis);
		}
		return imageFields;
	}

	/**
	 * Creates an Image entry in the database with the params
	 * 
	 * @param appId
	 * @param imageId
	 * @param directory
	 * @param type
	 * @param size
	 * @param resolution
	 * @param creationDate
	 * @param fileName
	 * @return
	 */
	public boolean createImageInApp(String appId, String imageId,
			String directory, String type, String size, String pixelsSize,
			String creationDate, String fileName, String location) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("images:time", unixTime, appId + ":" + imageId);
			jedis.hset("images:" + imageId, "dir", directory);
			jedis.hset("images:" + imageId, "type", type);
			jedis.hset("images:" + imageId, "size", size);
			jedis.hset("images:" + imageId, "resolution", pixelsSize);
			jedis.hset("images:" + imageId, "creationDate", creationDate);
			jedis.hset("images:" + imageId, "pixelsSize", pixelsSize);
			jedis.hset("images:" + imageId, "fileName", fileName);
			if(location != null)
				jedis.hset("images:" + imageId, "location", location);
			this.jedis.sadd("app:" + appId + ":images", imageId);
			sucess = true;
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public boolean createVideoInApp(String appId, String videoId,
			String directory, String type, String size, String resolution,
			String creationDate, String fileName, String location) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("video:time", unixTime, appId + ":" + videoId);
			this.jedis.hset("video:" + videoId, "dir", directory);
			this.jedis.hset("video:" + videoId, "type", type);
			this.jedis.hset("video:" + videoId, "size", size);
			this.jedis.hset("video:" + videoId, "resolution", resolution);
			this.jedis.hset("video:" + videoId, "creationDate", creationDate);
			this.jedis.hset("video:" + videoId, "fileName", fileName);
			if(location != null)
				this.jedis.hset("video:" + videoId, "location", location);
			this.jedis.sadd("app:" + appId + ":video", videoId);
			sucess = true;
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public Set<String> getAllStorageIds(String appId) {
		Jedis jedis = pool.getResource();
		Set<String> storageIds = null;
		try {
			storageIds = jedis.smembers("app:" + appId + ":storage");
		} finally {
			pool.returnResource(jedis);
		}
		return storageIds;
	}

	public boolean createStorageInApp(String appId, String storageId,
			String directory, String fileExtension, String fileSize,
			String creationDate, String fileName, String location) {
		Jedis jedis = pool.getResource();
		boolean sucess = true;
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("storage:time", unixTime, appId + ":" + storageId);
			jedis.hset("storage:" + storageId, "dir", directory);
			jedis.hset("storage:" + storageId, "type", fileExtension);
			jedis.hset("storage:" + storageId, "size", fileSize);
			jedis.hset("storage:" + storageId, "creationDate",
					creationDate);
			jedis.hset("storage:" + storageId, "fileName", fileName);
			if(location != null)
				jedis.hset("storage:" + storageId, "location", location);
			jedis.sadd("app:" + appId + ":storage", storageId);
			sucess = true;
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public boolean videoExistsInApp(String appId, String videoId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			Set<String> videoInApp = jedis.smembers("app:" + appId + ":video");
			Iterator<String> it = videoInApp.iterator();
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(videoId))
					sucess = true;
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	/**
	 * Deletes the video from Redis.
	 * 
	 * @param appId
	 * @param videoId
	 * @return
	 */
	public boolean deleteVideoInApp(String appId, String videoId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			System.out.println("reaching video deletion in redis");
			jedis.zrem("video:time", appId + ":" + videoId);
			jedis.srem("app:" + appId + ":video", videoId);
			jedis.del("video:" + videoId);
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	/**
	 * Deletes the image referenced by imageId from Redis.
	 * 
	 * @param appId
	 * @param imageId
	 * @return
	 */
	public boolean deleteImageInApp(String appId, String imageId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			jedis.zrem("image:time", appId + ":" + imageId);
			jedis.srem("app:" + appId + ":image", imageId);
			jedis.del("image:" + imageId);
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	public boolean deleteStorageInApp(String appId, String storageId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			jedis.zrem("storage:time", appId + ":" + storageId);
			jedis.srem("app:" + appId + ":storage", storageId);
			jedis.del("storage:" + storageId);
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	/**
	 * Returns the directory of the specified 'id' file.
	 * 
	 * @param appId
	 * @param id
	 * @param folderType
	 * @param requestType
	 * @return
	 */
	public String getFileDirectory(String appId, String id, String folderType,
			String requestType) {
		Jedis jedis = pool.getResource();
		String fileDirectory = null;
		try {
			fileDirectory = this.jedis.hget(requestType + ":" + id, "dir");
		} finally {
			pool.returnResource(jedis);
		}
		return fileDirectory;
	}

	/**
	 * Returns the 'videoId' video fields. If we want the video "Lion king", we
	 * get the size + dir + videoId, ect.
	 * 
	 * @param appId
	 * @param videoId
	 * @return
	 */
	public Map<String, String> getVideoInApp(String appId, String videoId) {
		Jedis jedis = pool.getResource();
		Map<String, String> videoFields = null;
		try {
			Set<String> videoInApp = jedis.smembers("app:" + appId + ":video");
			Iterator<String> it = videoInApp.iterator();
			boolean videoExistsForApp = false;
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(videoId))
					videoExistsForApp = true;
			if (videoExistsForApp)
				videoFields = jedis.hgetAll("video:" + videoId);
		} finally {
			pool.returnResource(jedis);
		}
		return videoFields;
	}

	@Override
	public boolean identifierInUseByUserInApp(String appId, String userId) {
		Jedis jedis = pool.getResource();
		boolean userExists = false;
		try {
			Set<String> usersInApp = this.jedis.smembers("app:" + appId
					+ ":users");
			Iterator<String> it = usersInApp.iterator();
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(userId))
					userExists = true;
		} finally {
			pool.returnResource(jedis);
		}
		return userExists;
	}

	@Override
	public String convertAppIdToAppName(String appId) {
		Jedis jedis = pool.getResource();
		String appName = null;
		try {
			if (jedis.exists("apps:" + appId)) {
				appName = jedis.hget("apps:" + appId, "appName");
			}
		} finally {
			pool.returnResource(jedis);
		}
		return appName;
	}

	@Override
	public String getUserNameUsingUserId(String appId, String userId) {
		Jedis jedis = pool.getResource();
		String userName = null;
		try {
			Set<String> usersInApp = this.jedis.smembers("app:" + appId
					+ ":users");
			Iterator<String> it = usersInApp.iterator();
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(userId))
					userName = jedis.hget("users:" + userId, "userName");
		} finally {
			pool.returnResource(jedis);
		}
		return userName;
	}

	@Override
	public String getEmailUsingUserId(String appId, String userId) {
		Jedis jedis = pool.getResource();
		String email = null;
		try {
			Set<String> usersInApp = this.jedis.smembers("app:" + appId
					+ ":users");
			Iterator<String> it = usersInApp.iterator();
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(userId))
					email = jedis.hget("users:" + userId, "email");
		} finally {
			pool.returnResource(jedis);
		}
		return email;
	}

	@Override
	public Map<String, String> getOldestElement() {
		System.out
				.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
		System.out
				.println("!!!!!!!!!!!!!!!!!!TESTING CACHE!!!!!!!!!!!!!!!!!!!!");
		Jedis jedis = pool.getResource();
		Map<String, String> oldestElementFields = null;
		try {
			Set<String> oldestAudioElement = jedis
					.zrevrange("audio:time", 1, 1);
			System.out.println("oldestAudioElement size: "
					+ oldestAudioElement.size());
			Double audioScore = jedis.zscore("audio:time", oldestAudioElement
					.iterator().next());
			System.out.println("AUDIO SCORE: " + audioScore);
			Set<String> oldestVideoElement = jedis
					.zrevrange("video:time", 1, 1);
			System.out.println("oldestVideoElement size: "
					+ oldestVideoElement.size());
			Double videoScore = jedis.zscore("video:time", oldestVideoElement
					.iterator().next());
			Set<String> oldestImageElement = jedis
					.zrevrange("image:time", 1, 1);
			System.out.println("oldestImageElement size: "
					+ oldestImageElement.size());
			Double imageScore = jedis.zscore("images:time", oldestImageElement
					.iterator().next());
			Set<String> oldestStorageElement = jedis.zrevrange("storage:time",
					1, 1);
			System.out.println("oldestStorageElement size: "
					+ oldestStorageElement.size());
			Double storageScore = jedis.zscore("storage:time",
					oldestStorageElement.iterator().next());
			String greater = compareScores(audioScore, videoScore, imageScore,
					storageScore);
			String[] splitted = greater.split(":");
			if (greater.equalsIgnoreCase(AUDIO))
				return getAudioInApp(splitted[0], splitted[1]);
			else if (greater.equalsIgnoreCase(VIDEO))
				return getVideoInApp(splitted[0], splitted[1]);
			else if (greater.equalsIgnoreCase(IMAGES))
				return getImageInApp(splitted[0], splitted[1]);
			else if (greater.equalsIgnoreCase(STORAGE))
				return getStorageInApp(splitted[0], splitted[1]);

		} finally {
			pool.returnResource(jedis);
		}
		return oldestElementFields;
	}

	private String compareScores(Double audioScore, Double videoScore,
			Double imageScore, Double storageScore) {
		if (audioScore > videoScore) {
			if (audioScore > imageScore) {
				if (audioScore > storageScore)
					return AUDIO;
				else
					return STORAGE;
			}
			if (imageScore > storageScore)
				return IMAGES;
			else
				return STORAGE;
		}
		if (videoScore > imageScore) {
			if (videoScore > storageScore)
				return VIDEO;
			else
				return STORAGE;
		}
		if (imageScore > storageScore)
			return IMAGES;
		else
			return STORAGE;
	}

	public void deleteOldestElement() {
		Jedis jedis = pool.getResource();
		try {
			Set<String> oldestAudioElement = jedis
					.zrevrange("audio:time", 1, 1);
			System.out.println("oldestAudioElement size: "
					+ oldestAudioElement.size());
			String appIdAudioId = oldestAudioElement.iterator().next();
			Double audioScore = jedis.zscore("audio:time", appIdAudioId);
			System.out.println("AUDIO SCORE: " + audioScore);

			Set<String> oldestVideoElement = jedis
					.zrevrange("video:time", 1, 1);
			System.out.println("oldestVideoElement size: "
					+ oldestVideoElement.size());
			String appIdVideoId = oldestVideoElement.iterator().next();
			Double videoScore = jedis.zscore("video:time", appIdVideoId);

			Set<String> oldestImageElement = jedis
					.zrevrange("image:time", 1, 1);
			System.out.println("oldestImageElement size: "
					+ oldestImageElement.size());
			String appIdImageId = oldestImageElement.iterator().next();
			Double imageScore = jedis.zscore("images:time", appIdImageId);

			Set<String> oldestStorageElement = jedis.zrevrange("storage:time",
					1, 1);
			System.out.println("oldestStorageElement size: "
					+ oldestStorageElement.size());
			String appIdStorageId = oldestStorageElement.iterator().next();
			Double storageScore = jedis.zscore("storage:time", appIdStorageId);

			String greater = compareScores(audioScore, videoScore, imageScore,
					storageScore);
			if (greater.equalsIgnoreCase(AUDIO))
				jedis.zrem("audio:time", appIdAudioId);
			else if (greater.equalsIgnoreCase(VIDEO))
				jedis.zrem("video:time", appIdVideoId);
			else if (greater.equalsIgnoreCase(IMAGES))
				jedis.zrem("images:time", appIdImageId);
			else if (greater.equalsIgnoreCase(STORAGE))
				jedis.zrem("storage:time", appIdStorageId);
		} finally {
			pool.returnResource(jedis);
		}
	}

	@Override
	public String getEmailUsingUserName(String appId, String userName) {
		Jedis jedis = pool.getResource();
		try {
			Set<String> usersInApp = jedis.smembers("app:" + appId + ":users");
			Iterator<String> it = usersInApp.iterator();
			while (it.hasNext()) {
				String userId = it.next();
				Map<String, String> userFields = jedis.hgetAll("users:"
						+ userId);
				if (userFields.get("userName").equalsIgnoreCase(userName))
					return userFields.get("email");
			}

		} finally {
			pool.returnResource(jedis);
		}
		return null;
	}

	@Override
	public String getUserIdUsingUserName(String appId, String userName) {
		Jedis jedis = pool.getResource();
		try {
			Set<String> usersInApp = jedis.smembers("app:" + appId + ":users");
			Iterator<String> it = usersInApp.iterator();
			while (it.hasNext()) {
				String userId = it.next();
				Map<String, String> userFields = jedis.hgetAll("users:"
						+ userId);
				if (userFields.get("userName").equalsIgnoreCase(userName))
					return userFields.get("userId");
			}
		} finally {
			pool.returnResource(jedis);
		}
		return null;
	}

	@Override
	public Set<String> getAllStorageIdsInApp(String appId) {
		Jedis jedis = pool.getResource();
		Set<String> op;
		try {
			op = jedis.smembers("app:" + appId + ":storage");
		}finally {
			pool.returnResource(jedis);
		}
		return op;
	}

	@Override
	/**
	 * Return codes: 1 = Created application -1 = Application exists;
	 * 
	 * @param appId
	 * @param creationDate
	 * @return
	 */
	public boolean createApp(String appId, String appName, String creationDate, boolean confirmUsersEmail) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (!jedis.exists("apps:" + appId)) {
				jedis.hset("apps:" + appId, "creationDate", creationDate);
				jedis.hset("apps:" + appId, "alive", "true");
				jedis.hset("apps:" + appId, "appName", appName);
				jedis.hset("apps:" + appId, "confirmUsersEmail", ""+confirmUsersEmail);
				long unixTime = System.currentTimeMillis() / 1000L;
				jedis.zadd("apps:time", unixTime, appId);
				sucess = true;
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;

	}

	@Override
	public void reviveApp(String appId) {
		Jedis jedis = pool.getResource();
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("apps:time", unixTime, appId);
			jedis.hset("apps:" + appId, "alive", "true");
		} finally {
			pool.returnResource(jedis);
		}
	}

	@Override
	public void updateUser(String appId, String userId, String email) {
		Jedis jedis = pool.getResource();
		try {
			jedis.hset("users:" + userId, "email", email);
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("users:time", unixTime, appId + ":" + userId);
		} finally {
			pool.returnResource(jedis);
		}
	}

	@Override
	public void updateUser(String appId, String userId, String email,
			byte[] hash, byte[] salt) {
		Jedis jedis = pool.getResource();
		try {
			jedis.hset("users:" + userId, "email", email);
			jedis.hset(("users:" + userId).getBytes(), "salt".getBytes(), salt);
			jedis.hset(("users:" + userId).getBytes(), "hash".getBytes(), hash);
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("users:time", unixTime, appId + ":" + userId);

		} finally {
			pool.returnResource(jedis);
		}
	}
	/**
	 * Not implemented.
	 */
	@Override
	public Set<String> getAllMediaIds(String appId) {
		Jedis jedis = pool.getResource();
		Set<String> mediaIds = new HashSet<String>();
		try {
			mediaIds.addAll(jedis.smembers("app:" + appId + ":audio"));
			mediaIds.addAll(jedis.smembers("app:" + appId + ":images"));
			mediaIds.addAll(jedis.smembers("app:" + appId + ":video"));
		} finally {
			pool.returnResource(jedis);
		}
		return mediaIds;		
	}

	@Override
	public Set<String> allCachedElements() {
		Jedis jedis = pool.getResource();
		Set<String> elements;
		try{
			elements = new HashSet<String>();
			elements.addAll(jedis
					.zrevrange("audio:time", 0, -1));
			elements.addAll(jedis
					.zrevrange("video:time", 0, -1));
			elements.addAll(jedis
					.zrevrange("image:time", 0, -1));
		}finally {
			pool.returnResource(jedis);
		}
		return elements;		
	}

	@Override
	public void updateUserLocationAndDate(String userId, String appId,
			String sessionToken, String location, String date) {
		Jedis jedis = pool.getResource();
		try{
			jedis.hset(("users:" + userId), "lastActive", date);
			jedis.hset(("users:" + userId), "location", location);
		}finally{
			pool.returnResource(jedis);
		}
	}

	@Override
	public boolean storageExistsInApp(String appId, String storageId) {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			Set<String> storageInApp = this.jedis.smembers("app:" + appId
					+ ":storage");
			Iterator<String> it = storageInApp.iterator();
			while (it.hasNext())
				if (it.next().equalsIgnoreCase(storageId))
					sucess = true;
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	@Override
	public boolean confirmUsersEmail(String appId) {
		Jedis jedis = pool.getResource();
		boolean confirmUsersEmail = false;
		try {
			confirmUsersEmail = Boolean.parseBoolean(this.jedis.hget("apps:"+appId, "confirmUsersEmail"));
		}finally {
			pool.returnResource(jedis);
		}
		return confirmUsersEmail;
	}

	@Override
	public boolean createUserWithFlagWithEmailConfirmation(String appId,
			String userId, String userName, String email, byte[] salt,
			byte[] hash, String creationDate, String flag,
			boolean emailConfirmed) throws UnsupportedEncodingException {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (!jedis.exists("users:" + userId)) {
				long unixTime = System.currentTimeMillis() / 1000L;
				jedis.zadd("users:time", unixTime, appId + ":" + userId);
				jedis.hset("users:" + userId, "userId", userId);
				jedis.hset("users:" + userId, "userName", userName);
				jedis.hset("users:" + userId, "email", email);
				jedis.hset("users:"+userId, "emailConfirmed", emailConfirmed+"");
				jedis.hset("users:" + userId, "salt",
						new String(salt,
								"ISO-8859-1"));
				jedis.hset(("users:" + userId), "lastActive", new Date().toString());
				jedis.hset(("users:" + userId), "flag", flag);
				jedis.hset(("users:" + userId), "hash",
						new String(hash, "ISO-8859-1"));
				jedis.hset("users:" + userId, "alive", new String("true"));
				jedis.hset("users:" + userId, "creationDate", creationDate);
				jedis.sadd("app:" + appId + ":users", userId);
				jedis.sadd("app:" + appId + ":users:emails", email);
				sucess = true;
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	@Override
	public boolean createUserWithoutFlagWithEmailConfirmation(String appId,
			String userId, String userName, String email, byte[] salt,
			byte[] hash, String creationDate, boolean emailConfirmed) throws UnsupportedEncodingException {
		Jedis jedis = pool.getResource();
		boolean sucess = false;
		try {
			if (!jedis.exists("users:" + userId)) {
				long unixTime = System.currentTimeMillis() / 1000L;
				jedis.zadd("users:time", unixTime, appId + ":" + userId);
				jedis.hset("users:" + userId, "userId", userId);
				jedis.hset("users:" + userId, "userName", userName);
				jedis.hset("users:" + userId, "email", email);
				jedis.hset("users:"+userId, "emailConfirmed", emailConfirmed+"");
				jedis.hset("users:" + userId, "salt",
						new String(salt,
								"ISO-8859-1"));
				jedis.hset(("users:" + userId), "lastActive", new Date().toString());
				jedis.hset(("users:" + userId), "hash",
						new String(hash, "ISO-8859-1"));
				jedis.hset("users:" + userId, "alive", new String("true"));
				jedis.hset("users:" + userId, "creationDate", creationDate);
				jedis.sadd("app:" + appId + ":users", userId);
				jedis.sadd("app:" + appId + ":users:emails", email);
				sucess = true;
			}
		} finally {
			pool.returnResource(jedis);
		}
		return sucess;
	}

	@Override
	public boolean confirmUserEmail(String appId, String userId) {
		Jedis jedis = pool.getResource();
		try {
			jedis.hset("users:"+userId, "emailConfirmed", true+"");
		}finally {
			pool.returnResource(jedis);
		}
		return true;
	}

	@Override
	public boolean userEmailIsConfirmed(String appId, String userId) {
		Jedis jedis = pool.getResource();
		boolean isConfirmed = false;
		try {
			isConfirmed = Boolean.parseBoolean(jedis.hget("users:"+userId, "emailConfirmed"));
		}finally {
			pool.returnResource(jedis);
		}
		return isConfirmed;
	}

	@Override
	public boolean updateAllAppFields(String appId, String alive,
			String newAppName, boolean confirmUsersEmail) {
		Jedis jedis = pool.getResource();
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("apps:time", unixTime, appId);
			jedis.hset("apps:" + appId, "appName", newAppName);
			jedis.hset("apps:" + appId, "alive", alive);
			jedis.hset("apps:" + appId, "appName", newAppName);
			jedis.hset("apps:" + appId, "confirmUsersEmail", ""+confirmUsersEmail);
		} finally {
			pool.returnResource(jedis);
		}
		return true;
	}

	@Override
	public boolean updateConfirmUsersEmailOption(String appId,
			Boolean confirmUsersEmail) {
		Jedis jedis = pool.getResource();
		try {
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("apps:time", unixTime, appId);
			jedis.hset("apps:" + appId, "confirmUsersEmail", ""+confirmUsersEmail);
		} finally {
			pool.returnResource(jedis);
		}
		return true;
	}

	@Override
	public boolean updateUserPassword(String appId, String userId, byte[] hash,
			byte[] salt) throws UnsupportedEncodingException {
		Jedis jedis = pool.getResource();
		try {
			jedis.hset(("users:" + userId).getBytes(), "salt".getBytes(), salt);
			jedis.hset(("users:" + userId).getBytes(), "hash".getBytes(), hash);
			long unixTime = System.currentTimeMillis() / 1000L;
			jedis.zadd("users:time", unixTime, appId + ":" + userId);
		} finally {
			pool.returnResource(jedis);
		}
		return true;
	}
	@Override
	public void destroyPool(){
		pool.destroy();
	}
}
