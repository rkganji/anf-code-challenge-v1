package com.anf.core.services.impl;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import com.anf.core.error.AppException;
import com.anf.core.error.ErrorCode;
import com.anf.core.model.AgeConfig;
import com.anf.core.model.User;
import com.anf.core.services.ContentService;

import lombok.extern.log4j.Log4j;

@Component(
        immediate = true,
        service = ContentService.class)
@Designate(
        ocd = ContentServiceImpl.Config.class)
@Log4j
public class ContentServiceImpl implements ContentService {
    
    // OSGI configurations
    @ObjectClassDefinition(
            name = "ANF Code Challenge - Content Service Configuration",
            description = "OSGI service providing configuration options for Content Service")
    @interface Config {
        
        @AttributeDefinition(
                name = "Age Config Path",
                description = "Node path that contains age configuration for validation")
        String age_config_path() default "/etc/age";
        
        @AttributeDefinition(
                name = "Users Root Path",
                description = "Root path under which user details should be saved")
        String users_root_path() default "/var/anf-code-challenge";
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    private String ageConfigPath;
    private String usersRootPath;

    @Activate
    protected void activate(Config config) {
        this.ageConfigPath = config.age_config_path();
        this.usersRootPath = config.users_root_path();
    }

    /*
     * (non-Javadoc)
     * @see com.anf.core.services.ContentService#commitUserDetails(com.anf.core.model.User)
     */
    @Override
    public void commitUserDetails(User user) throws RepositoryException, LoginException {
        // Add your logic. Modify method signature as per need.

        // validate inputs
        validate(user);

        // commit user
        commit(user);
    }

    private void validate(User user) {

        // Considering all user fields as mandatory and throwing an error otherwise
        if (StringUtils.isEmpty(user.getFirstName()) || StringUtils.isEmpty(user.getLastName())
                || StringUtils.isEmpty(user.getCountry())) {
            throw new AppException(ErrorCode.USER_FIELDS_MISSING);
        }

        AgeConfig ageConfig = null;
        try {
            
            // Read age configuration for validation
            ageConfig = getAgeConfig();

            int age = user.getAge();

            // throw an error if age is not within configured range
            if (age < Integer.valueOf(ageConfig.getMinAge()) || age > Integer.valueOf(ageConfig.getMaxAge())) {
                throw new AppException(ErrorCode.USER_AGE_INELIGIBLE);
            }
            
        } catch (RepositoryException | LoginException e) {
            // in case unable to access the age configuration node
            log.error("Unable to read age configurations for validation", e);
            throw new AppException(ErrorCode.AGE_CONFIG_ISSUE);
        }
    }

    private AgeConfig getAgeConfig() throws RepositoryException, LoginException {

        // Access the age config node and read min and max age values configured 
        Node ageConfigNode = resolverFactory.getServiceResourceResolver(null)
                .getResource(ageConfigPath)
                .adaptTo(Node.class);

        return AgeConfig.builder()
                .minAge(ageConfigNode.getProperty("minAge")
                        .getString())
                .maxAge(ageConfigNode.getProperty("maxAge")
                        .getString())
                .build();

    }

    private void commit(User user) throws RepositoryException, LoginException {

        // Get session based on configured service user for this bundle
        Session session = resolverFactory.getServiceResourceResolver(null)
                .adaptTo(Session.class);

        // Under user's root node, sub nodes are created based on first character
        // of first name to organize user nodes better instead of creating all user nodes
        // under single folder.
        final String firstName = user.getFirstName()
                .toLowerCase();
        final char firstChar = firstName.charAt(0);
        Node usersRootNode = JcrUtils.getOrCreateByPath(usersRootPath + "/" + firstChar, "sling:Folder",
                "sling:OrderedFolder", session, true);

        // Create user node using first name for easy readability with node repository.
        // Numbered suffix will be automatically added when same first name is used e.g., john, john0, john1 etc.
        Node userNode = JcrUtils.getOrCreateUniqueByPath(usersRootNode, firstName, "nt:unstructured");

        // save user information as properties
        userNode.setProperty("firstName", user.getFirstName());
        userNode.setProperty("lastName", user.getLastName());
        userNode.setProperty("country", user.getCountry());
        userNode.setProperty("age", user.getAge());

        // save changes
        session.save();
    }

}
