/*
 * *********************************************************
 * Copyright (c) 2009 - 2014, DHBW Mannheim - Tigers Mannheim
 * Project: TIGERS - Sumatra
 * Date: Mar 17, 2014
 * Author(s): Nicolai Ommer <nicolai.ommer@gmail.com>
 * *********************************************************
 */
package com.github.configurable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.tree.ConfigurationNode;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.log4j.Logger;

import com.github.g3force.s2vconverter.String2ValueConverter;


/**
 * Read from a given set of classes all {@link Configurable} annotations
 * and fill the associated fields with data from config file
 * 
 * @author Nicolai Ommer <nicolai.ommer@gmail.com>
 */
public class ConfigAnnotationProcessor
{
	private static final Logger					log				= Logger.getLogger(ConfigAnnotationProcessor.class
																						.getName());
																						
	private final List<ConfigurableFieldData>	data				= new ArrayList<ConfigurableFieldData>();
	private final Map<Field, String>				currentSpezis	= new HashMap<>();
	private static String2ValueConverter		s2vConv			= String2ValueConverter.getDefault();
																				
	private static class ConfigurableFieldData
	{
		private String		className;
		private String		fieldName;
		private String		fieldValue;
		private String		fieldSpezi;
		private String		comment;
		private Class<?>	fieldType;
	}
	
	
	/**
	 */
	public ConfigAnnotationProcessor()
	{
	}
	
	
	private List<Class<?>> getAllSubClasses(final Class<?> mainClazz)
	{
		Class<?> clazz = mainClazz;
		final List<Class<?>> classes = new ArrayList<Class<?>>();
		while ((clazz != null) && !clazz.equals(Object.class))
		{
			classes.add(clazz);
			clazz = clazz.getSuperclass();
		}
		return classes;
	}
	
	
	/**
	 * Create a configuration representation from the currently set values in the given classes
	 * 
	 * @param classes search for {@link Configurable} annotation in all this classes. Subclasses are NOT automatically
	 *           considered!
	 * @param name Name of the root node for the resulting configuration object
	 * @return
	 */
	public HierarchicalConfiguration getDefaultConfig(final Set<Class<?>> classes, final String name)
	{
		final HierarchicalConfiguration config = new HierarchicalConfiguration();
		config.setDelimiterParsingDisabled(true);
		
		if (classes.isEmpty())
		{
			return config;
		}
		
		Set<Class<?>> classesAndSubClasses = new LinkedHashSet<Class<?>>(classes);
		for (Class<?> clazz : classes)
		{
			classesAndSubClasses.addAll(getAllSubClasses(clazz));
		}
		
		Map<Class<?>, List<ConfigurableFieldData>> classesAndSubClassesMap = new LinkedHashMap<Class<?>, List<ConfigurableFieldData>>();
		for (Class<?> clazz : classesAndSubClasses)
		{
			List<ConfigurableFieldData> dataRead = read(clazz);
			if (!dataRead.isEmpty())
			{
				classesAndSubClassesMap.put(clazz, dataRead);
			}
		}
		
		config.getRoot().setName(name);
		for (Map.Entry<Class<?>, List<ConfigurableFieldData>> entry : classesAndSubClassesMap.entrySet())
		{
			Class<?> clazz = entry.getKey();
			String clazzName = clazz.getName();
			String clazzKey = clazzName;
			
			List<ConfigurableFieldData> dataRead = entry.getValue();
			for (ConfigurableFieldData fieldData : dataRead)
			{
				String spezi = fieldData.fieldSpezi.isEmpty() ? "" : ":" + fieldData.fieldSpezi;
				final HierarchicalConfiguration cfg = new HierarchicalConfiguration();
				cfg.setDelimiterParsingDisabled(true);
				cfg.addProperty(clazzKey + "." + fieldData.fieldName + spezi, escape(fieldData.fieldValue));
				cfg.addProperty(clazzKey + "." + fieldData.fieldName + spezi + "[@comment]", escape(fieldData.comment));
				cfg.addProperty(clazzKey + "." + fieldData.fieldName + spezi + "[@class]",
						fieldData.fieldType.getName());
				config.append(cfg);
			}
		}
		
		return config;
	}
	
	
	private Map<String, ConfigurationNode> getClassNodesFromConfigRec(final String basePackage,
			final List<ConfigurationNode> nodes)
	{
		Map<String, ConfigurationNode> classes = new LinkedHashMap<String, ConfigurationNode>();
		for (ConfigurationNode node : nodes)
		{
			String className = basePackage + "." + node.getName();
			try
			{
				Class.forName(className);
			} catch (ClassNotFoundException err)
			{
				classes.putAll(getClassNodesFromConfigRec(className, node.getChildren()));
				continue;
			}
			classes.put(className, node);
		}
		return classes;
	}
	
	
	/**
	 * (re)load configuration from given config object.
	 * Note: It will not be applies yet, use one of the apply methods for this.
	 * 
	 * @param config full merged config to be used
	 */
	public void loadConfiguration(final HierarchicalConfiguration config)
	{
		data.clear();
		
		List<ConfigurationNode> attrs = config.getRoot().getAttributes("base");
		String base = "";
		if (attrs.size() == 1)
		{
			base = attrs.get(0).getValue().toString();
		}
		
		List<ConfigurationNode> classNodes = config.getRoot().getChildren();
		
		Map<String, ConfigurationNode> classes = getClassNodesFromConfigRec(base, classNodes);
		
		for (Map.Entry<String, ConfigurationNode> entry : classes.entrySet())
		{
			String className = entry.getKey();
			List<ConfigurationNode> fieldNodes = entry.getValue().getChildren();
			for (ConfigurationNode fieldNode : fieldNodes)
			{
				String[] split = fieldNode.getName().split(":");
				String fieldName = split[0];
				String fieldSpezi = split.length > 1 ? split[1] : "";
				String fieldValue = fieldNode.getValue() == null ? "" : unescape(s2vConv.toString(
						fieldNode
								.getValue().getClass(),
						fieldNode.getValue()));
				ConfigurableFieldData fieldData = new ConfigurableFieldData();
				fieldData.className = className;
				fieldData.fieldName = fieldName;
				fieldData.fieldValue = fieldValue;
				fieldData.fieldSpezi = fieldSpezi;
				fieldData.comment = "";
				data.add(fieldData);
			}
		}
	}
	
	
	private String escape(final String str)
	{
		return StringEscapeUtils.escapeXml(str);
	}
	
	
	private String unescape(final String str)
	{
		return StringEscapeUtils.unescapeXml(str);
	}
	
	
	/**
	 * Apply given spezi. Note: No default values will be applied, if spezi is set.
	 * Use spezi="" to apply default values
	 * 
	 * @param spezi
	 */
	public void apply(final String spezi)
	{
		for (ConfigurableFieldData fieldData : data)
		{
			if (fieldData.fieldSpezi.equals(spezi))
			{
				try
				{
					Class<?> clazz = Class.forName(fieldData.className);
					write(clazz, null, fieldData);
				} catch (ClassNotFoundException err)
				{
					log.error("Could not find class with name " + fieldData.className);
				}
			}
		}
	}
	
	
	/**
	 */
	public void applyAll()
	{
		for (ConfigurableFieldData fieldData : data)
		{
			try
			{
				Class<?> clazz = Class.forName(fieldData.className);
				for (Field field : clazz.getDeclaredFields())
				{
					if (field.getName().equals(fieldData.fieldName))
					{
						String curSpezi = currentSpezis.get(field);
						if ((curSpezi == null) || curSpezi.equals(fieldData.fieldSpezi))
						{
							write(clazz, null, fieldData);
						}
						break;
					}
				}
			} catch (ClassNotFoundException err)
			{
				log.warn("Could not find class with name " + fieldData.className);
			}
		}
	}
	
	
	/**
	 * Apply values to all fields of the given object. SubClasses will be considered.
	 * 
	 * @param obj
	 * @param spezi
	 */
	public void apply(final Object obj, final String spezi)
	{
		Class<?> clazz = obj.getClass();
		do
		{
			for (ConfigurableFieldData fieldData : data)
			{
				if (fieldData.className.equals(clazz.getName()) && fieldData.fieldSpezi.equals(spezi))
				{
					write(clazz, obj, fieldData);
				}
			}
			clazz = clazz.getSuperclass();
		} while ((clazz != null) && !clazz.equals(Object.class));
	}
	
	
	/**
	 * Apply values to all fields of the given class. SubClasses will be considered.
	 * 
	 * @param clazz
	 * @param spezi
	 */
	public void apply(final Class<?> clazz, final String spezi)
	{
		Class<?> c = clazz;
		do
		{
			for (ConfigurableFieldData fieldData : data)
			{
				if (fieldData.className.equals(c.getName()) && fieldData.fieldSpezi.equals(spezi))
				{
					write(c, null, fieldData);
				}
			}
			c = c.getSuperclass();
		} while ((c != null) && !c.equals(Object.class));
	}
	
	
	/**
	 * Read values from fields and generate {@link ConfigurableFieldData}
	 * 
	 * @param clazz
	 * @return
	 */
	private List<ConfigurableFieldData> read(final Class<?> clazz)
	{
		List<ConfigurableFieldData> dataRead = new ArrayList<ConfigurableFieldData>();
		
		for (Field field : clazz.getDeclaredFields())
		{
			if (field.isAnnotationPresent(Configurable.class))
			{
				Class<?> type = field.getType();
				String name = field.getName();
				field.setAccessible(true);
				
				if ((field.getModifiers() & Modifier.FINAL) != 0)
				{
					log.error("Configurable field " + clazz.getName() + "#" + name + " must not be final");
					continue;
				}
				
				String value;
				if ((field.getModifiers() & Modifier.STATIC) == 0)
				{
					value = field.getAnnotation(Configurable.class).defValue();
					if (value.isEmpty())
					{
						log.warn("No default value spezified with defValue for non-static field " + clazz.getName() + "#"
								+ name);
					}
				} else
				{
					try
					{
						value = s2vConv.toString(type, field.get(null));
					} catch (IllegalArgumentException err1)
					{
						log.error("Could not get value of field " + name, err1);
						continue;
					} catch (IllegalAccessException err1)
					{
						log.error("Could not get value of field " + name, err1);
						continue;
					}
				}
				
				String comment = field.getAnnotation(Configurable.class).comment();
				String[] spezis = field.getAnnotation(Configurable.class).spezis();
				
				if (spezis.length == 0)
				{
					ConfigurableFieldData fieldData = new ConfigurableFieldData();
					fieldData.className = clazz.getName();
					fieldData.fieldName = name;
					fieldData.fieldValue = value;
					fieldData.fieldSpezi = "";
					fieldData.comment = comment;
					fieldData.fieldType = type;
					dataRead.add(fieldData);
				} else
				{
					for (String spezi : spezis)
					{
						ConfigurableFieldData fieldDataSpezi = new ConfigurableFieldData();
						fieldDataSpezi.className = clazz.getName();
						fieldDataSpezi.fieldName = name;
						fieldDataSpezi.fieldValue = value;
						fieldDataSpezi.fieldSpezi = spezi;
						fieldDataSpezi.comment = comment;
						fieldDataSpezi.fieldType = type;
						dataRead.add(fieldDataSpezi);
					}
				}
			}
		}
		return dataRead;
	}
	
	
	/**
	 * Write provided fieldData into field of clazz.
	 * 
	 * @param clazz The class containing the field in fieldData
	 * @param obj null for static fields or the field instance
	 * @param fieldData information about what to write into the field
	 */
	private void write(final Class<?> clazz, final Object obj, final ConfigurableFieldData fieldData)
	{
		for (Field field : clazz.getDeclaredFields())
		{
			if (field.getName().equals(fieldData.fieldName))
			{
				field.setAccessible(true);
				Class<?> type = field.getType();
				boolean isStatic = ((field.getModifiers() & Modifier.STATIC) != 0);
				if (!isStatic && (obj == null))
				{
					return;
				}
				Object value = s2vConv.parseString(type, fieldData.fieldValue);
				try
				{
					field.set(obj, value);
					currentSpezis.put(field, fieldData.fieldSpezi);
				} catch (IllegalArgumentException err)
				{
					log.error("Could not set value on field " + field.getName(), err);
				} catch (IllegalAccessException err)
				{
					log.error("Could not set value on field " + field.getName(), err);
				}
				return;
			}
		}
		log.warn("Could not find field: " + fieldData.fieldName);
	}
	
	
	/**
	 * Merge values from cfg into cfgBase. cfg may not contain fields other than in cfgBase.
	 * cfgBase will be modified!
	 * 
	 * @param cfgBase
	 * @param cfg
	 */
	public static void merge(final HierarchicalConfiguration cfgBase, final HierarchicalConfiguration cfg)
	{
		for (ConfigurationNode classNode : cfg.getRootNode().getChildren())
		{
			String className = classNode.getName();
			
			if (!className.matches("[A-Z].*")
					&& !cfgBase.configurationsAt(className).isEmpty()
					&& !cfg.configurationsAt(className).isEmpty())
			{
				// is not a class, so step deeper in hierarchy
				merge(cfgBase.configurationAt(className),
						cfg.configurationAt(className));
				continue;
			}
			
			if (!className.matches("[A-Z].*"))
			{
				// is not a class, so step deeper in hierarchy
				if (cfg.containsKey(className))
				{
					merge(cfgBase.configurationAt(className), cfg.configurationAt(className));
				}
				continue;
			}
			
			List<ConfigurationNode> classNodeChildren = cfgBase.getRootNode().getChildren(className);
			if (classNodeChildren.size() > 1)
			{
				log.error("Expected at most one child, but was: " + classNodeChildren);
				continue;
			} else if (classNodeChildren.isEmpty())
			{
				log.warn("Config for class " + className + " vanished.");
				continue;
			}
			
			ConfigurationNode classNodeBase = classNodeChildren.get(0);
			for (ConfigurationNode fieldNode : classNode.getChildren())
			{
				String fieldName = fieldNode.getName();
				List<ConfigurationNode> fieldNodeChildren = classNodeBase.getChildren(fieldName);
				if (fieldNodeChildren.size() > 1)
				{
					log.error("Expected at most one child, but was: " + fieldNodeChildren);
					continue;
				} else if (fieldNodeChildren.isEmpty())
				{
					log.warn("Config for field " + className + "#" + fieldName + " vanished.");
					continue;
				}
				ConfigurationNode fieldNodeBase = fieldNodeChildren.get(0);
				fieldNodeBase.setValue(fieldNode.getValue());
			}
		}
	}
}
