/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.properties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.handler.IgnoreErrorsBindHandler;
import org.springframework.boot.context.properties.bind.handler.IgnoreNestedPropertiesBindHandler;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * {@link BeanPostProcessor} to bind {@link PropertySources} to beans annotated with
 * {@link ConfigurationProperties}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Christian Dupuis
 * @author Stephane Nicoll
 * @author Madhura Bhave
 */
public class ConfigurationPropertiesBindingPostProcessor implements BeanPostProcessor,
		BeanFactoryAware, EnvironmentAware, ApplicationContextAware, InitializingBean,
		DisposableBean, ApplicationListener<ContextRefreshedEvent>, PriorityOrdered {

	/**
	 * The bean name of the configuration properties validator.
	 */
	public static final String VALIDATOR_BEAN_NAME = "configurationPropertiesValidator";

	private static final String[] VALIDATOR_CLASSES = { "javax.validation.Validator",
			"javax.validation.ValidatorFactory" };

	private static final Log logger = LogFactory
			.getLog(ConfigurationPropertiesBindingPostProcessor.class);

	private ConfigurationBeanFactoryMetaData beans = new ConfigurationBeanFactoryMetaData();

	private PropertySources propertySources;

	private Validator validator;

	private volatile Validator localValidator;

	private ConversionService conversionService;

	private DefaultConversionService defaultConversionService;

	private BeanFactory beanFactory;

	private Environment environment = new StandardEnvironment();

	private ApplicationContext applicationContext;

	private List<Converter<?, ?>> converters = Collections.emptyList();

	private List<GenericConverter> genericConverters = Collections.emptyList();

	private int order = Ordered.HIGHEST_PRECEDENCE + 1;

	private ConfigurationPropertySources configurationSources;

	private Binder binder;

	/**
	 * A list of custom converters (in addition to the defaults) to use when converting
	 * properties for binding.
	 * @param converters the converters to set
	 */
	@Autowired(required = false)
	@ConfigurationPropertiesBinding
	public void setConverters(List<Converter<?, ?>> converters) {
		this.converters = converters;
	}

	/**
	 * A list of custom converters (in addition to the defaults) to use when converting
	 * properties for binding.
	 * @param converters the converters to set
	 */
	@Autowired(required = false)
	@ConfigurationPropertiesBinding
	public void setGenericConverters(List<GenericConverter> converters) {
		this.genericConverters = converters;
	}

	/**
	 * Set the order of the bean.
	 * @param order the order
	 */
	public void setOrder(int order) {
		this.order = order;
	}

	/**
	 * Return the order of the bean.
	 * @return the order
	 */
	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the property sources to bind.
	 * @param propertySources the property sources
	 */
	public void setPropertySources(PropertySources propertySources) {
		this.propertySources = propertySources;
	}

	/**
	 * Set the bean validator used to validate property fields.
	 * @param validator the validator
	 */
	public void setValidator(Validator validator) {
		this.validator = validator;
	}

	/**
	 * Set the conversion service used to convert property values.
	 * @param conversionService the conversion service
	 */
	public void setConversionService(ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	/**
	 * Set the bean meta-data store.
	 * @param beans the bean meta data store
	 */
	public void setBeanMetaDataStore(ConfigurationBeanFactoryMetaData beans) {
		this.beans = beans;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		if (this.propertySources == null) {
			this.propertySources = deducePropertySources();
		}
		if (this.validator == null) {
			this.validator = getOptionalBean(VALIDATOR_BEAN_NAME, Validator.class);
		}
		if (this.conversionService == null) {
			this.conversionService = getOptionalBean(
					ConfigurableApplicationContext.CONVERSION_SERVICE_BEAN_NAME,
					ConversionService.class);
		}
		this.configurationSources = ConfigurationPropertySources
				.get(this.propertySources);
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		freeLocalValidator();
	}

	@Override
	public void destroy() throws Exception {
		freeLocalValidator();
	}

	private void freeLocalValidator() {
		try {
			Validator validator = this.localValidator;
			this.localValidator = null;
			if (validator != null) {
				((DisposableBean) validator).destroy();
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private PropertySources deducePropertySources() {
		PropertySourcesPlaceholderConfigurer configurer = getSinglePropertySourcesPlaceholderConfigurer();
		if (configurer != null) {
			return configurer.getAppliedPropertySources();
		}
		if (this.environment instanceof ConfigurableEnvironment) {
			return ((ConfigurableEnvironment) this.environment).getPropertySources();
		}
		throw new IllegalStateException("Unable to obtain PropertySources from "
				+ "PropertySourcesPlaceholderConfigurer or Environment");
	}

	private PropertySourcesPlaceholderConfigurer getSinglePropertySourcesPlaceholderConfigurer() {
		// Take care not to cause early instantiation of all FactoryBeans
		if (this.beanFactory instanceof ListableBeanFactory) {
			ListableBeanFactory listableBeanFactory = (ListableBeanFactory) this.beanFactory;
			Map<String, PropertySourcesPlaceholderConfigurer> beans = listableBeanFactory
					.getBeansOfType(PropertySourcesPlaceholderConfigurer.class, false,
							false);
			if (beans.size() == 1) {
				return beans.values().iterator().next();
			}
			if (beans.size() > 1 && logger.isWarnEnabled()) {
				logger.warn("Multiple PropertySourcesPlaceholderConfigurer "
						+ "beans registered " + beans.keySet()
						+ ", falling back to Environment");
			}
		}
		return null;
	}

	private <T> T getOptionalBean(String name, Class<T> type) {
		try {
			return this.beanFactory.getBean(name, type);
		}
		catch (NoSuchBeanDefinitionException ex) {
			return null;
		}
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName)
			throws BeansException {
		ConfigurationProperties annotation = getAnnotation(bean, beanName);
		if (annotation != null) {
			postProcessBeforeInitialization(bean, beanName, annotation);
		}
		return bean;
	}

	private ConfigurationProperties getAnnotation(Object bean, String beanName) {
		ConfigurationProperties annotation = this.beans.findFactoryAnnotation(beanName,
				ConfigurationProperties.class);
		if (annotation == null) {
			annotation = AnnotationUtils.findAnnotation(bean.getClass(),
					ConfigurationProperties.class);
		}
		return annotation;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName)
			throws BeansException {
		return bean;
	}

	private void postProcessBeforeInitialization(Object bean, String beanName,
			ConfigurationProperties annotation) {
		Binder binder = getBinder();
		Validator validator = determineValidator(bean);
		BindHandler handler = getBindHandler(annotation, validator);
		Bindable<?> bindable = Bindable.ofInstance(bean);
		try {
			binder.bind(annotation.prefix(), bindable, handler);
		}
		catch (Exception ex) {
			String targetClass = ClassUtils.getShortName(bean.getClass());
			throw new BeanCreationException(beanName, "Could not bind properties to "
					+ targetClass + " (" + getAnnotationDetails(annotation) + ")", ex);
		}
	}

	private Binder getBinder() {
		Binder binder = this.binder;
		if (binder == null) {
			ConversionService conversionService = this.conversionService;
			if (conversionService == null) {
				conversionService = getDefaultConversionService();
			}
			binder = new Binder(this.configurationSources,
					new PropertySourcesPlaceholdersResolver(this.propertySources),
					conversionService);
			this.binder = binder;
		}
		return binder;
	}

	private ConversionService getDefaultConversionService() {
		if (this.defaultConversionService == null) {
			DefaultConversionService conversionService = new DefaultConversionService();
			this.applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
			for (Converter<?, ?> converter : this.converters) {
				conversionService.addConverter(converter);
			}
			for (GenericConverter genericConverter : this.genericConverters) {
				conversionService.addConverter(genericConverter);
			}
			this.defaultConversionService = conversionService;
		}
		return this.defaultConversionService;
	}

	private String getAnnotationDetails(ConfigurationProperties annotation) {
		if (annotation == null) {
			return "";
		}
		StringBuilder details = new StringBuilder();
		details.append("prefix=").append(annotation.prefix());
		details.append(", ignoreInvalidFields=").append(annotation.ignoreInvalidFields());
		details.append(", ignoreUnknownFields=").append(annotation.ignoreUnknownFields());
		details.append(", ignoreNestedProperties=")
				.append(annotation.ignoreNestedProperties());
		return details.toString();
	}

	private Validator determineValidator(Object bean) {
		Validator validator = getValidator();
		boolean supportsBean = (validator != null && validator.supports(bean.getClass()));
		if (ClassUtils.isAssignable(Validator.class, bean.getClass())) {
			if (supportsBean) {
				return new ChainingValidator(validator, (Validator) bean);
			}
			return (Validator) bean;
		}
		return (supportsBean ? validator : null);
	}

	private Validator getValidator() {
		if (this.validator != null) {
			return this.validator;
		}
		if (this.localValidator == null && isJsr303Present()) {
			this.localValidator = new ValidatedLocalValidatorFactoryBean(
					this.applicationContext);
		}
		return this.localValidator;
	}

	private boolean isJsr303Present() {
		for (String validatorClass : VALIDATOR_CLASSES) {
			if (!ClassUtils.isPresent(validatorClass,
					this.applicationContext.getClassLoader())) {
				return false;
			}
		}
		return true;
	}

	private BindHandler getBindHandler(ConfigurationProperties annotation,
			Validator validator) {
		BindHandler handler = BindHandler.DEFAULT;
		if (annotation.ignoreInvalidFields()) {
			handler = new IgnoreErrorsBindHandler(handler);
		}
		if (!annotation.ignoreUnknownFields()) {
			handler = new NoUnboundElementsBindHandler(handler,
					annotation.ignoreNestedProperties());
		}
		if (annotation.ignoreNestedProperties()) {
			handler = new IgnoreNestedPropertiesBindHandler(handler);
		}
		if (validator != null) {
			handler = new ValidationBindHandler(handler, validator);
		}
		return handler;
	}

	/**
	 * {@link LocalValidatorFactoryBean} supports classes annotated with
	 * {@link Validated @Validated}.
	 */
	private static class ValidatedLocalValidatorFactoryBean
			extends LocalValidatorFactoryBean {

		private static final Log logger = LogFactory
				.getLog(ConfigurationPropertiesBindingPostProcessor.class);

		ValidatedLocalValidatorFactoryBean(ApplicationContext applicationContext) {
			setApplicationContext(applicationContext);
			setMessageInterpolator(new MessageInterpolatorFactory().getObject());
			afterPropertiesSet();
		}

		@Override
		public boolean supports(Class<?> type) {
			if (!super.supports(type)) {
				return false;
			}
			if (AnnotatedElementUtils.hasAnnotation(type, Validated.class)) {
				return true;
			}
			if (type.getPackage().getName().startsWith("org.springframework.boot")) {
				return false;
			}
			if (getConstraintsForClass(type).isBeanConstrained()) {
				logger.warn("The @ConfigurationProperties bean " + type
						+ " contains validation constraints but had not been annotated "
						+ "with @Validated.");
			}
			return true;
		}

	}

	/**
	 * {@link Validator} implementation that wraps {@link Validator} instances and chains
	 * their execution.
	 */
	private static class ChainingValidator implements Validator {

		private final Validator[] validators;

		ChainingValidator(Validator... validators) {
			Assert.notNull(validators, "Validators must not be null");
			this.validators = validators;
		}

		@Override
		public boolean supports(Class<?> clazz) {
			for (Validator validator : this.validators) {
				if (validator.supports(clazz)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void validate(Object target, Errors errors) {
			for (Validator validator : this.validators) {
				if (validator.supports(target.getClass())) {
					validator.validate(target, errors);
				}
			}
		}

	}

}
