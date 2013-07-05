/*
 * Copyright 2013 Alex Lin.
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
package org.opoo.press.impl;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.LocaleUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opoo.press.Application;
import org.opoo.press.Category;
import org.opoo.press.Context;
import org.opoo.press.Converter;
import org.opoo.press.Generator;
import org.opoo.press.Page;
import org.opoo.press.Plugin;
import org.opoo.press.Post;
import org.opoo.press.Renderer;
import org.opoo.press.Site;
import org.opoo.press.SiteBuilder;
import org.opoo.press.StaticFile;
import org.opoo.press.Tag;
import org.opoo.press.converter.IdentityConverter;
import org.opoo.press.highlighter.Highlighter;
import org.opoo.press.plugin.DefaultPlugin;
import org.opoo.press.source.NoFrontMatterException;
import org.opoo.press.source.Source;
import org.opoo.press.source.SourceEntry;
import org.opoo.press.source.SourceEntryLoader;
import org.opoo.press.source.SourceParser;
import org.opoo.press.template.TitleCaseModel;
import org.opoo.press.util.ClassUtils;
import org.opoo.press.util.MapUtils;
import org.yaml.snakeyaml.Yaml;

import freemarker.template.TemplateModel;

/**
 * @author Alex Lin
 *
 */
public class SiteImpl implements Site, SiteBuilder{
	private static final Log log = LogFactory.getLog(SiteImpl.class);
	private static final boolean IS_DEBUG_ENABLED = log.isDebugEnabled();
	
	private Map<String, Object> config;
	private Map<String, Object> data;
	private File source;
	private File dest;
	private File templates;
	private File assets;
	private File working;
	
	private String root;
	
	private List<Page> pages;
	private List<Post> posts;
	private List<StaticFile> staticFiles;
	
//	private Map<String, List<Post>> categories;
//	private Map<String, List<Post>> tags;
//	private Map<String, String> categoryNames;
//	private Map<String, String> tagNames;
	
	private Map<String, Category> categories;
	private List<Tag> tags;
	
	private Date time;
	private boolean showDrafts;
	
//	private transient List<Generator> generators;
//	private transient SiteFilter siteFilter;
	private transient Renderer renderer;
//	private List<String> includes;
//	private List<String> excludes;
	private RegistryImpl registry;
	private Locale locale;
	private Highlighter highlighter;
	
	
	public SiteImpl(File siteDir){
		this(siteDir, null);
	}
	
	public SiteImpl(File siteDir, Map<String,Object> extraConfig){
		this(loadConfig(siteDir, extraConfig));
	}

	public SiteImpl(Map<String, Object> config) {
		super();
		this.config = config;
		//fix root
		root = fixAndGetRoot(config);

		this.showDrafts = MapUtils.get(config, "show_drafts", false);
		this.data = new HashMap<String,Object>(config);

		reset();
		setup();
	}
	
	@SuppressWarnings("unchecked")
	private static Map<String, Object> loadConfig(File siteDir, Map<String,Object> extraConfig){
		Context context = Application.getContext();
		Yaml yaml = context.getYaml();
		File configFile = new File(siteDir, "config.yml");
		Map<String,Object> config = null;
		try {
			config = (Map<String, Object>) yaml.load(new FileInputStream(configFile));
		} catch (FileNotFoundException e) {
			throw new IllegalArgumentException("Site config file not found: " + e.getMessage());
		}
		
		if(extraConfig != null && !extraConfig.isEmpty()){
			log.debug("Merge extra config to main config map.");
			config.putAll(extraConfig);
		}
		
		config.put("site", siteDir);
		
		//show drafts
		if("true".equals(config.get("show_drafts"))){
			log.info("+ Show drafts option set 'ON'");
		}
		
		//debug option
		boolean debug = "true".equals(config.get("debug"));
		
		if(debug){
			for(Map.Entry<String, Object> en: config.entrySet()){
				String name = en.getKey();
				name = StringUtils.leftPad(name, 25);
				log.info(name + ": " + en.getValue());
			}
		}
		return config;
	}
	
	static String fixAndGetRoot(Map<String, Object> config){
		String rootUrl = (String)config.get("root");
		if(rootUrl == null){
			config.put("root", "");
			return "";
		}
		rootUrl = rootUrl.trim();
		if(rootUrl.equals("/") || "".equals(rootUrl)){
			config.put("root", "");
			return "";
		}
		if(rootUrl.endsWith("/")){
			rootUrl = StringUtils.removeEnd(rootUrl, "/");
		}
		if(!rootUrl.startsWith("/")){
			rootUrl = "/" + rootUrl;
		}
		config.put("root", rootUrl);
		return rootUrl;
	}
	
	private void setupDirs(){
		File site = null;
		Object siteObject = config.get("site");
		if(siteObject instanceof File){
			site = (File) siteObject;
		}else{
			String siteDir = (String) siteObject;
			site = new File(siteDir);
		}
		
//		String siteDir = (String) config.get("site");
//		File site = new File(siteDir);
		if(!site.exists() || !site.isDirectory() || !site.canRead()){
			throw new IllegalArgumentException("Site directory not exists or not a directory: " + site);
		}
		
		source = new File(site, "source");
		if(!source.exists() || !source.isDirectory() || !source.canRead()){
			throw new IllegalArgumentException("Source directory not exists or not a directory.");
		}
		
		templates = new File(site, "templates");
		if(!templates.exists() || !templates.isDirectory()){
			templates = new File(source, "_templates");
		}
		if(!templates.exists() || !templates.isDirectory()){
			throw new IllegalArgumentException("No valid templates directory in site or source.");
		}
		
		assets = new File(site, "assets");
		if(!assets.exists() || !assets.isDirectory()){
			assets = null;
		}
		
		//target directories
		String destDir = (String) config.get("destination");
		if(destDir != null){
			this.dest = new File(destDir);
		}else{
			try {
				this.dest = new File(site, "../public/" + site.getName());
				dest = dest.getCanonicalFile();
			} catch (IOException e) {
				throw new IllegalArgumentException("Prepare destination directory error", e);
			}
		}
//		if(!dest.exists()){
//			dest.mkdirs();
//		}

		String workingDir = (String) config.get("working_dir");
		if(workingDir != null){
			this.working = new File(workingDir);
		}else{
			String tmpdir = System.getProperty("java.io.tmpdir");
			System.out.println(tmpdir);
			working = new File(tmpdir, "opoopresscache/" + site.getName());
		}
//		if(!working.exists()){
//			working.mkdirs();
//		}
		
		if(dest.equals(source) || source.getAbsolutePath().startsWith(dest.getAbsolutePath())){
			throw new IllegalArgumentException("Destination directory cannot be or contain the Source directory.");
		}
	}
	
	public void build(){
		reset();
		read();
		generate();
		render();
		cleanup();
		write();
	}

	void reset(){
		this.time = (Date) config.get("time");
		if(time == null){
			time = new Date();
		}
		this.pages = new ArrayList<Page>();
		this.posts = new ArrayList<Post>();
		this.staticFiles = new ArrayList<StaticFile>();
		
		resetCategories();
		resetTags();
	}
	
	void resetCategories(){
		this.categories = new LinkedHashMap<String,Category>();
		@SuppressWarnings("unchecked")
		Map<String,String> names = (Map<String,String>) config.get("category_names");
		if(names == null || names.isEmpty()){
			return;
		}
		//sort name
		names = new TreeMap<String,String>(names);
		for(Map.Entry<String, String> en: names.entrySet()){
			String path = en.getKey();
			String name = en.getValue();
			
			String nicename = path;
			String parentPath = null;
			int index = path.lastIndexOf('.');
			if(index != -1){
				nicename = path.substring(index + 1);
				parentPath = path.substring(0, index);
			}
			
			Category parent = null;
			if(parentPath != null){
				parent = categories.get(parentPath);
				if(parent == null){
					throw new IllegalArgumentException("Parent category not found: " + parentPath);
				}
			}
			CategoryImpl category = new CategoryImpl(nicename, name, parent, this);
			categories.put(path, category);
		}
	}
	
	void resetTags(){
		this.tags = new ArrayList<Tag>();
		@SuppressWarnings("unchecked")
		Map<String,String> names = (Map<String, String>) config.get("tag_names");
		if(names == null || names.isEmpty()){
			return;
		}
		
		for(Map.Entry<String, String> en: names.entrySet()){
			 tags.add(new TagImpl(en.getKey(), en.getValue(), this));
		 }
	}
	
	void setup(){
		setupDirs();
		
		String localeString = (String) config.get("locale");
		if(localeString != null){
			locale = LocaleUtils.toLocale(localeString);
			log.debug("Set locale: " + locale);
		}
		
		String highlighterClassName = (String) config.get("highlighter");
		if(highlighterClassName != null){
			highlighter = (Highlighter) ClassUtils.newInstance(highlighterClassName, this);
			log.debug("Set highlighter: " + highlighterClassName);
		}
		
		this.registry = new RegistryImpl(this);
		//register default converter
		this.registry.registerConverter(new IdentityConverter());
		
		//plugins
		new DefaultPlugin().initialize(registry);
		
		@SuppressWarnings("unchecked")
		List<String> pluginClassNames = (List<String>) config.get("plugins");
		if(pluginClassNames != null && !pluginClassNames.isEmpty()){
			for(String className: pluginClassNames){
				Plugin p = (Plugin) ClassUtils.newInstance(className);
				p.initialize(registry);
			}
		}
		
		//Construct RendererImpl after initializing all plugins
		this.renderer = new RendererImpl(this, registry.getTemplateLoaders());
	}


	void read(){
		log.info("Reading sources ...");
		
		SourceEntryLoader loader = Application.getContext().getSourceEntryLoader();
		SourceParser parser = Application.getContext().getSourceParser();
		List<SourceEntry> list = loader.loadSourceEntries(source, buildFilter());
		for(SourceEntry en: list){
			read(en, parser);
		}
		
		Collections.sort(posts);
		Collections.reverse(posts);
		setPostNextOrPrevious(posts);
		
//		sort(categories);
//		sort(tags);
		
		postRead();
	}
	
	/**
	 * @param posts2
	 */
	private void setPostNextOrPrevious(List<Post> posts) {
		for(int i = 0 ; i < posts.size() ; i++){
			Post post = posts.get(i);
			if(i > 0){
				post.setNext(posts.get(i - 1));
			}
			if(i < posts.size() - 1){
				post.setPrevious(posts.get(i + 1));
			}
		}
	}


	/**
	 * 
	 */
	private void postRead() {
		registry.getSiteFilter().postRead(this);
	}

	/**
	 * @param en
	 */
	private void read(SourceEntry en, SourceParser parser) {
		try {
			Source src = parser.parse(en);
			Map<String, Object> map = src.getMeta();
			String layout = (String) map.get("layout");
			if("post".equals(layout)){
				readPost(src);
			}else{
				pages.add(new PageImpl(this, src));
			}
		} catch (NoFrontMatterException e) {
			this.staticFiles.add(new StaticFileImpl(this, en));
		}
	}
	
	private void readPost(Source src){
		if(isDraft(src.getMeta())){
			if(showDrafts){
				posts.add(new Draft(this, src));
			}
		}else{
			posts.add(new PostImpl(this, src));
		}
	}
	
	private boolean isDraft(Map<String, Object> meta){
		if(!meta.containsKey("published")){
			return false;
		}
		Boolean b = (Boolean)meta.get("published");
		return !b.booleanValue();
	}
	
	FileFilter buildFilter(){
		@SuppressWarnings("unchecked")
		final List<String> includes = (List<String>) config.get("includes");
		@SuppressWarnings("unchecked")
		final List<String> excludes = (List<String>) config.get("excludes");
		return new FileFilter(){
			@Override
			public boolean accept(File file) {
				String name = file.getName();
				if(includes != null && includes.contains(name)){
					return true;
				}
				if(excludes != null && excludes.contains(name)){
					return false;
				}
				char firstChar = name.charAt(0);
				if(firstChar == '.' || firstChar == '_' || firstChar == '#'){
					return false;
				}
				char lastChar = name.charAt(name.length() - 1);
				if(lastChar == '~'){
					return false;
				}
				if(file.isHidden()){
					return false;
				}
				return true;
			}
		};
	}
	
	
	void generate(){
		for(Generator g: registry.getGenerators()){
			g.generate(this);
		}
		postGenerate();
	}
	
	/**
	 * 
	 */
	private void postGenerate() {
		registry.getSiteFilter().postGenerate(this);
	}


	void render(){
		log.info("Rendering ...");
		
		Map<String, Object> rootMap = buildRootMap();
		
		for(Post post: posts){
			post.render(rootMap);
		}
		
		for(Page page: pages){
			page.render(rootMap);
		}
		postRender();
	}
	
	/**
	 * 
	 */
	private void postRender() {
		registry.getSiteFilter().postRender(this);
	}

	Map<String,Object> buildRootMap(){
		Map<String, Object> map = new HashMap<String,Object>();
		map.put("site", this);
		
//		String rootUrl = (String)config.get("root");
		map.put("root_url", getRoot());
		
		Map<String, TemplateModel> models = registry.getTemplateModels();
		if(models != null && !models.isEmpty()){
			map.putAll(models);
		}
		
		TitleCaseModel model = new TitleCaseModel(this);
		map.put("titleCase", model);
		map.put("titlecase", model);
		return map;
	}
	
	/**
	 * 
	 */
	void cleanup() {
		List<File> destFiles = getAllDestFiles(dest);
		List<File> files = new ArrayList<File>();

		for(Post post: posts){
			files.add(post.getOutputFile(dest));
		}
		for(Page page: pages){
			files.add(page.getOutputFile(dest));
		}
		for(StaticFile staticFile: staticFiles){
			files.add(staticFile.getOutputFile(dest));
		}
		
		//find obsolete files
		for(File file: files){
			destFiles.remove(file);
		}
		
		//delete obsolete files
		if(!destFiles.isEmpty()){
			for(File destFile: destFiles){
				FileUtils.deleteQuietly(destFile);
				if(IS_DEBUG_ENABLED){
					log.debug("Delete file " + destFile);
				}
			}
		}
		
		//call post cleanup
		postCleanup();
	}
	
	/**
	 * @param dest2
	 * @return
	 */
	private List<File> getAllDestFiles(File dest) {
		List<File> files = new ArrayList<File>();
		if(dest != null && dest.exists()){
			listDestFiles(files, dest);
		}
		return files;
	}
	
	private void listDestFiles(List<File> files, File dir){
		File[] list = dir.listFiles();
		for(File f: list){
			if(f.isFile()){
				files.add(f);
			}else if(f.isDirectory()){
				listDestFiles(files, f);
			}
		}
	}

	/**
	 * 
	 */
	private void postCleanup() {
	}


	void write(){
		log.info("Writing files ...");

		if(!dest.exists()){
			dest.mkdirs();
		}

		log.info("Writing " + posts.size() + " posts");
		for(Post post: posts){
			post.write(dest);
		}
		
		log.info("Writing " + pages.size() + " pages");
		for(Page page: pages){
			page.write(dest);
		}
		
		if(!staticFiles.isEmpty()){
			log.info("Copying " + staticFiles.size() + " static files");
			for(StaticFile sf: staticFiles){
				sf.write(dest);
			}
		}

		if(assets != null){
			try {
				log.info("Copying 1 assets directory");
				log.debug("Copying assets...");
				FileUtils.copyDirectory(assets, dest, buildFilter());
				
				log.debug("All asset files copied.");
			} catch (IOException e) {
				log.error("Copy assets error", e);
			}
		}
		postWrite();
	}

	/**
	 * 
	 */
	private void postWrite() {
		registry.getSiteFilter().postWrite(this);
	}

	/**
	 * @return the pages
	 */
	public List<Page> getPages() {
		return pages;
	}

	/**
	 * @return the posts
	 */
	public List<Post> getPosts() {
		return posts;
	}

	/* (non-Javadoc)
	 * @see org.opoo.joctopress.Site#getConfig()
	 */
	@Override
	public Map<String, Object> getConfig() {
		return config;
	}

	/* (non-Javadoc)
	 * @see org.opoo.joctopress.Site#getSouce()
	 */
	@Override
	public File getSource() {
		return source;
	}

	/* (non-Javadoc)
	 * @see org.opoo.joctopress.Site#getDestination()
	 */
	@Override
	public File getDestination() {
		return dest;
	}
	
	public List<StaticFile> getStaticFiles(){
		return staticFiles;
	}

	/* (non-Javadoc)
	 * @see org.opoo.joctopress.Site#getTime()
	 */
	@Override
	public Date getTime() {
		return time;
	}

	public Object get(String name){
		return data.get(name);
	}

	/* (non-Javadoc)
	 * @see org.opoo.joctopress.Site#getRenderer()
	 */
	@Override
	public Renderer getRenderer() {
		return renderer;
	}

	@Override
	public File getTemplates() {
		return templates;
	}

	@Override
	public File getAssets() {
		return assets;
	}

	@Override
	public File getWorking() {
		return working;
	}

	@Override
	public Converter getConverter(Source source) {
		return registry.getConverter(source);
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.Site#getRoot()
	 */
	@Override
	public String getRoot() {
		return root;
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.Site#getLocale()
	 */
	@Override
	public Locale getLocale() {
		return locale;
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.Site#getHighlighter()
	 */
	@Override
	public Highlighter getHighlighter() {
		return highlighter;
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.Site#getCategories()
	 */
	@Override
	public List<Category> getCategories() {
		//return categories;
		return new CategoriesList(categories);
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.Site#getTags()
	 */
	@Override
	public List<Tag> getTags() {
		return tags;
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.SiteHelper#buildCanonical(java.lang.String)
	 */
	@Override
	public String buildCanonical(String url) {
		String canonical = (String) config.get("url");
		String permalink = (String) config.get("permalink");
		String pageUrl = url;
		if(permalink != null && permalink.endsWith(".html")){
			canonical += pageUrl;
		}else{
			canonical += StringUtils.removeEnd(pageUrl, "index.html");
		}
		return canonical;
	}

//	/* (non-Javadoc)
//	 * @see org.opoo.press.SiteHelper#buildCategoryUrl(org.opoo.press.Category)
//	 */
//	@Override
//	public String buildCategoryUrl(Category category) {
//		//String rootUrl = (String) site.getConfig().get("root");
//		String categoryDir = (String) config.get("category_dir");
//		return /*rootUrl + */ categoryDir + "/" + category.getUrl() + "/";
//	}

//	/* (non-Javadoc)
//	 * @see org.opoo.press.SiteHelper#buildTagUrl(org.opoo.press.Tag)
//	 */
//	@Override
//	public String buildTagUrl(TagImpl tag) {
//		String tagDir = (String) config.get("tag_dir");
//		return /*rootUrl + */ tagDir + "/" + Utils.encodeURL(tag.getSlug()) + "/";
//	}

	/* (non-Javadoc)
	 * @see org.opoo.press.SiteHelper#toSlug(java.lang.String)
	 */
	@Override
	public String toSlug(String tagName) {
		return toNicename(tagName);
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.SiteHelper#toNicename(java.lang.String)
	 */
	@Override
	public String toNicename(String categoryName) {
		//TODO: translate, pinyin or others
		//String nicenameConverter = config.get("category_nicename_converter");
		//CategoryNicenameConverter converter = newInstance(nicenameConverter);
		//return converter.convert(categoryName);
		
		categoryName = categoryName.toLowerCase();
		return StringUtils.replace(categoryName, " ", "-");
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.SiteHelper#getCategory(java.lang.String)
	 */
	@Override
	public Category getCategory(String categoryNameOrNicename) {
		if(categories == null || categories.isEmpty()){
			return null;
		}
		//If path equals
		if(categories.containsKey(categoryNameOrNicename)){
			return categories.get(categoryNameOrNicename);
		}
		for(Category category: categories.values()){
			if(category.isNameOrNicename(categoryNameOrNicename)){
				return category;
			}
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see org.opoo.press.SiteHelper#getTag(java.lang.String)
	 */
	@Override
	public Tag getTag(String tagNameOrSlug) {
		if(tags == null || tags.isEmpty()){
			return null;
		}
		for(Tag tag: tags){
			if(tag.isNameOrSlug(tagNameOrSlug)){
				return tag;
			}
		}
		return null;
	}
	
	
	private static class CategoriesList extends AbstractList<Category>{
		private final List<Category> list;
		private final Map<String, Category> categories;
		
		private CategoriesList(Map<String, Category> categories) {
			this.categories = categories;
			this.list = new ArrayList<Category>(categories.values());
		}

		@Override
		public boolean add(Category category){
			categories.put(category.getPath(), category);
			return list.add(category);
		}
		
		/* (non-Javadoc)
		 * @see java.util.AbstractList#get(int)
		 */
		@Override
		public Category get(int index) {
			return list.get(index);
		}

		/* (non-Javadoc)
		 * @see java.util.AbstractCollection#size()
		 */
		@Override
		public int size() {
			return list.size();
		}
	}
}
