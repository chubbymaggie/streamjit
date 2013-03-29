package edu.mit.streamjit.util.json;

import com.google.common.primitives.Primitives;
import java.lang.reflect.Array;
import java.util.Arrays;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonValue;

/**
 * ArrayJsonifierFactory converts (possibly nested) arrays to and from JSON.
 * Primitive arrays are supported.
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 3/27/2013
 */
public final class ArrayJsonifierFactory implements JsonifierFactory {
	@Override
	@SuppressWarnings({"unchecked","rawtypes"})
	public <T> Jsonifier<T> getJsonifier(Class<T> klass) {
		if (klass.isArray())
			return new ArrayJsonifier(klass.getComponentType());
		return null;
	}

	/**
	 * In order to work with arrays of objects and primitives at the same time,
	 * we do everything in terms of Object and use java.lang.reflect.Array to
	 * get, store, and create arrays for us.  This might be a bit slow, but it's
	 * much more maintainable than having a separate Jsonifier for each
	 * primitive type.
	 */
	@SuppressWarnings("rawtypes")
	private static final class ArrayJsonifier implements Jsonifier {
		private final Class<?> componentType;
		/**
		 * It isn't possible to write a Jsonifier for primitive types, so we
		 * have to use the wrapper class when deserializing.  Array then takes
		 * care of unwrapping when storing in the primitive array.
		 */
		private final Class<?> jsonifierType;
		private ArrayJsonifier(Class<?> componentType) {
			assert componentType != null;
			this.componentType = componentType;
			if (componentType.isPrimitive())
				this.jsonifierType = Primitives.wrap(componentType);
			else
				this.jsonifierType = componentType;
		}
		@Override
		public Object fromJson(JsonValue value) {
			JsonArray jsonArray = (JsonArray)value;
			Object array = Array.newInstance(componentType, jsonArray.size());
			for (int i = 0; i < jsonArray.size(); ++i)
				Array.set(array, i, Jsonifiers.fromJson(jsonArray.get(i), jsonifierType));
			return array;
		}
		@Override
		public JsonValue toJson(Object t) {
			JsonArrayBuilder builder = Json.createArrayBuilder();
			int length = Array.getLength(t);
			for (int i = 0; i < length; ++i)
				builder.add(Jsonifiers.toJson(Array.get(t, i)));
			return builder.build();
		}
	}

	public static void main(String[] args) {
		Integer[] boxed = new Integer[]{1, 2};
		String boxedJson = Jsonifiers.toJson(boxed).toString();
		System.out.println(boxedJson);
		Integer[] boxedDeserialized = Jsonifiers.fromJson(boxedJson, Integer[].class);
		System.out.println(Arrays.toString(boxedDeserialized));

		int[] unboxed = new int[]{1, 2};
		String unboxedJson = Jsonifiers.toJson(unboxed).toString();
		System.out.println(unboxedJson);
		int[] unboxedDeserialized = Jsonifiers.fromJson(unboxedJson, int[].class);
		System.out.println(Arrays.toString(unboxedDeserialized));

		int[][] nested = {{1, 2}, {3, 4}};
		String nestedJson = Jsonifiers.toJson(nested).toString();
		System.out.println(nestedJson);
		int[][] nestedDeserialized = Jsonifiers.fromJson(nestedJson, int[][].class);
		System.out.println(Arrays.deepToString(nestedDeserialized));
	}
}