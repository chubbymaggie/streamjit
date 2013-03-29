package edu.mit.streamjit.util.json;

import edu.mit.streamjit.util.ReflectionUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;

/**
 * A Jsonifier that serializes objects whose behavior is entirely contained in
 * their code (i.e., objects with no fields) by writing their class name and
 * creating new instances.
 *
 * Specifically, this factory handles classes that have no nonstatic fields
 * (including superclasses) and an accessible no-arg constructor.
 *
 * TODO: support no-arg factory functions, as many stateless things will have
 * singleton instances accessed through a factory function.  Perhaps with an
 * annotation?
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 3/26/2013
 */
public final class StatelessJsonifierFactory implements JsonifierFactory, Jsonifier<Object> {
	/**
	 * The eligibility check involves expensive reflective operations, so we use
	 * a cache.  The cache is thread-safe to meet the thread safety requirements
	 * of JsonifierFactory.  Note that while we might perform the reflective
	 * operations multiple times if multiple threads ask about a class we
	 * haven't seen before, doing so is completely harmless.
	 */
	private final ConcurrentMap<Class<?>, Boolean> eligibleClasses = new ConcurrentHashMap<>();
	@Override
	@SuppressWarnings("unchecked")
	public <T> Jsonifier<T> getJsonifier(Class<T> klass) {
		Boolean value = eligibleClasses.get(klass);
		if (value != null)
			return (Jsonifier<T>)(value ? this : null);
		return (Jsonifier<T>)(isClassEligible(klass) ? this : null);
	}

	private boolean isClassEligible(Class<?> klass) {
		//Does it have state (a non-static field)?
		for (Field f : ReflectionUtils.getAllFields(klass))
			if (!Modifier.isStatic(f.getModifiers()))
				return false;
		//Does it have an accessible no-arg constructor?  Rather than
		//reimplement the access check, just try it.
		try {
			klass.newInstance();
		} catch (InstantiationException | IllegalAccessException ex) {
			return false;
		}
		return true;
	}

	@Override
	public Object fromJson(JsonValue value) {
		try {
			Class<?> klass = Jsonifiers.fromJson(((JsonObject)value).getJsonString("class"), Class.class);
			return klass.newInstance();
		} catch (ClassCastException | NullPointerException ex) {
			throw new JsonSerializationException(null, ex, value);
		} catch (InstantiationException | IllegalAccessException ex) {
			//We checked this in isClassEligible, so we shouldn't ever get here.
			throw new JsonSerializationException("Can't happen!", ex, value);
		}
	}

	@Override
	public JsonValue toJson(Object t) {
		//No Python-side support here (__class__ parameter) -- there's no state
		//here, so Python should never have to inspect this object.  (If Python
		//creates actual objects here, it has to know about every class that
		//might be serialized; if it just treats these objects as opaque strings,
		//it doesn't have to know about any of them.  Easy decision!)
		return Json.createObjectBuilder().add("class", Jsonifiers.toJson(t.getClass())).build();
	}
}