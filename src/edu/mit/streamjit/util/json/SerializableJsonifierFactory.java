package edu.mit.streamjit.util.json;

import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.jeffreybosboom.serviceproviderprocessor.ServiceProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * A JsonifierFactory that supports types implementing Serializable by
 * serializing them to a base64-encoded string.
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 3/29/2013
 */
@ServiceProvider(value = JsonifierFactory.class, priority = Integer.MAX_VALUE)
public final class SerializableJsonifierFactory implements JsonifierFactory {
	@Override
	@SuppressWarnings("unchecked")
	public <T> Jsonifier<T> getJsonifier(Class<T> klass) {
		return (Serializable.class.isAssignableFrom(klass) ? new SerializableJsonifier<>(klass) : null);
	}

	//The bound should be <T extends Serializable> but that would require a
	//cast above in getJsonifier().
	private static final class SerializableJsonifier<T> implements Jsonifier<T> {
		private final Class<T> klass;
		private SerializableJsonifier(Class<T> klass) {
			assert Serializable.class.isAssignableFrom(klass);
			this.klass = klass;
		}
		@Override
		public T fromJson(JsonValue value) {
			JsonObject obj = Jsonifiers.checkClassEqual(value, klass);
			String str = obj.getString("data");
			try (StringReader string = new StringReader(str);
					InputStream baseDecodingStream = BaseEncoding.base64Url().decodingStream(string);
					ObjectInputStream ois = new ObjectInputStream(baseDecodingStream)) {
				return klass.cast(ois.readObject());
			} catch (ClassNotFoundException | IOException ex) {
				throw new JsonSerializationException(ex);
			}
		}
		@Override
		public JsonValue toJson(T t) {
			try (StringWriter string = new StringWriter();
					OutputStream baseEncodingStream = BaseEncoding.base64Url().encodingStream(string);
					ObjectOutputStream oos = new ObjectOutputStream(baseEncodingStream)) {
				oos.writeObject(t);
				oos.flush();
				oos.close();
				return Json.createObjectBuilder()
						.add("class", Jsonifiers.toJson(t.getClass()))
						.add("data", string.toString())
						.build();
			} catch (IOException ex) {
				throw new JsonSerializationException(ex);
			}
		}
	}

	public static void main(String[] args) {
		Jsonifier<ArrayList> j = new SerializableJsonifierFactory().getJsonifier(ArrayList.class);
		JsonValue v = j.toJson(Lists.newArrayList(2, 4, 6));
		System.out.println(v);
		System.out.println(Jsonifiers.fromJson(v, List.class));
	}
}
