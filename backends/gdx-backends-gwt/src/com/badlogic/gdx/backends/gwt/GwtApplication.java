/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.gwt;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backends.gwt.preloader.Preloader;
import com.badlogic.gdx.backends.gwt.preloader.Preloader.PreloaderCallback;
import com.badlogic.gdx.backends.gwt.preloader.Preloader.PreloaderState;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.TimeUtils;
import com.google.gwt.animation.client.AnimationScheduler;
import com.google.gwt.animation.client.AnimationScheduler.AnimationCallback;
import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.logical.shared.ResizeEvent;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HasVerticalAlignment;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.InlineHTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Implementation of an {@link Application} based on GWT. Clients have to override {@link #getConfig()} and
 * {@link #createApplicationListener()}. Clients can override the default loading screen via {@link #getPreloaderCallback()} and
 * implement any loading screen drawing via GWT widgets.
 * @author mzechner */
public abstract class GwtApplication implements EntryPoint, Application {
	private ApplicationListener listener;
	GwtApplicationConfiguration config;
	GwtGraphics graphics;
	private GwtAudio audio;
	private GwtInput input;
	private GwtNet net;
	private Panel root = null;
	protected TextArea log = null;
	private int logLevel = LOG_ERROR;
	private ApplicationLogger applicationLogger;
	protected final Array<Runnable> runnables = new Array<Runnable>();
	protected final Array<Runnable> runnablesHelper = new Array<Runnable>();
	protected final Array<LifecycleListener> lifecycleListeners = new Array<LifecycleListener>();
	private int lastWidth;
	private int lastHeight;
	Preloader preloader;
	private static AgentInfo agentInfo;
	private ObjectMap<String, Preferences> prefs = new ObjectMap<String, Preferences>();
	private Clipboard clipboard;
	LoadingListener loadingListener;

	/** @return the configuration for the {@link GwtApplication}. */
	public abstract GwtApplicationConfiguration getConfig ();

	public String getPreloaderBaseURL () {
		return GWT.getHostPageBaseURL() + "assets/";
	}

	@Override
	public ApplicationListener getApplicationListener () {
		return listener;
	}

	public abstract ApplicationListener createApplicationListener ();

	@Override
	public void onModuleLoad () {
		GwtApplication.agentInfo = computeAgentInfo();
		this.listener = createApplicationListener();
		this.config = getConfig();
		setApplicationLogger(new GwtApplicationLogger(this.config.log));

		if (config.rootPanel != null) {
			this.root = config.rootPanel;
		} else {
			Element element = Document.get().getElementById("embed-" + GWT.getModuleName());
			int width;
			int height;

			if (!config.isFixedSizeApplication()) {
				// resizable application
				width = Window.getClientWidth() - config.padHorizontal;
				height = Window.getClientHeight() - config.padVertical;

				// resizeable application does not need to take the native screen density into
				// account here - the panel's size is set to logical pixels

				Window.enableScrolling(false);
				Window.setMargin("0");
				Window.addResizeHandler(new ResizeListener());
			} else {
				// fixed size application
				width = config.width;
				height = config.height;

				if (config.usePhysicalPixels) {
					double density = GwtGraphics.getNativeScreenDensity();
					width = (int)(width / density);
					height = (int)(height / density);
				}
			}

			if (element == null) {
				VerticalPanel panel = new VerticalPanel();
				panel.setWidth("" + width + "px");
				panel.setHeight("" + height + "px");
				panel.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);
				panel.setVerticalAlignment(HasVerticalAlignment.ALIGN_MIDDLE);
				RootPanel.get().add(panel);
				RootPanel.get().setWidth("" + width + "px");
				RootPanel.get().setHeight("" + height + "px");
				this.root = panel;
			} else {
				VerticalPanel panel = new VerticalPanel();
				panel.setWidth("" + width + "px");
				panel.setHeight("" + height + "px");
				panel.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);
				panel.setVerticalAlignment(HasVerticalAlignment.ALIGN_MIDDLE);
				element.appendChild(panel.getElement());
				root = panel;
			}
		}

		preloadAssets();
	}

	void preloadAssets () {
		final PreloaderCallback callback = getPreloaderCallback();
		preloader = createPreloader();
		preloader.preload("assets.txt", new PreloaderCallback() {
			@Override
			public void error (String file) {
				callback.error(file);
			}

			@Override
			public void update (PreloaderState state) {
				callback.update(state);
				if (state.hasEnded()) {
					getRootPanel().clear();
					if (loadingListener != null) loadingListener.beforeSetup();
					setupLoop();
					addEventListeners();
					if (loadingListener != null) loadingListener.afterSetup();
				}
			}
		});
	}

	/** Override this method to return a custom widget informing the that their browser lacks support of WebGL.
	 *
	 * @return Widget to display when WebGL is not supported. */
	public Widget getNoWebGLSupportWidget () {
		return new Label("Sorry, your browser doesn't seem to support WebGL");
	}

	void setupLoop () {
		Gdx.app = this;
		// setup modules
		try {
			graphics = new GwtGraphics(root, config);
		} catch (Throwable e) {
			root.clear();
			error("GwtApplication", "exception: " + e.getMessage(), e);
			root.add(getNoWebGLSupportWidget());
			return;
		}
		lastWidth = graphics.getWidth();
		lastHeight = graphics.getHeight();
		Gdx.graphics = graphics;
		Gdx.gl20 = graphics.getGL20();
		Gdx.gl = Gdx.gl20;
		if (config.disableAudio) {
			audio = null;
		} else {
			audio = createAudio();
		}
		Gdx.audio = audio;
		Gdx.files = createFiles();
		this.input = createInput(graphics.canvas, this.config);
		Gdx.input = this.input;
		this.net = new GwtNet(config);
		Gdx.net = this.net;
		this.clipboard = new GwtClipboard();
		updateLogLabelSize();

		// tell listener about app creation
		try {
			listener.create();
			listener.resize(graphics.getWidth(), graphics.getHeight());
		} catch (Throwable t) {
			error("GwtApplication", "exception: " + t.getMessage(), t);
			t.printStackTrace();
			throw new RuntimeException(t);
		}

		setupMainLoop();
	}

	protected void setupMainLoop () {
		AnimationScheduler.get().requestAnimationFrame(new AnimationCallback() {
			@Override
			public void execute (double timestamp) {
				try {
					mainLoop();
				} catch (Throwable t) {
					error("GwtApplication", "exception: " + t.getMessage(), t);
					throw new RuntimeException(t);
				}
				AnimationScheduler.get().requestAnimationFrame(this, graphics.canvas);
			}
		}, graphics.canvas);
	}

	void mainLoop () {
		graphics.update();
		if (Gdx.graphics.getWidth() != lastWidth || Gdx.graphics.getHeight() != lastHeight) {
			lastWidth = graphics.getWidth();
			lastHeight = graphics.getHeight();
			Gdx.gl.glViewport(0, 0, lastWidth, lastHeight);
			GwtApplication.this.listener.resize(lastWidth, lastHeight);
		}
		runnablesHelper.addAll(runnables);
		runnables.clear();
		for (int i = 0; i < runnablesHelper.size; i++) {
			runnablesHelper.get(i).run();
		}
		runnablesHelper.clear();
		graphics.frameId++;
		listener.render();
		input.reset();
	}

	public Panel getRootPanel () {
		return root;
	}

	long loadStart = TimeUtils.nanoTime();

	public Preloader createPreloader () {
		return new Preloader(getPreloaderBaseURL());
	}

	/** This procedure creates the preloader panel and returns a preloader callback to update it. <br />
	 * You can override it to construct your own preloader animation. You can adjust the progress bar colors to your needs by
	 * overriding {@link #adjustMeterPanel(Panel, Style)}. <br />
	 * Example to use an own image (width should be around 300px) placed in webapp folder:
	 * 
	 * <pre>
	 * public PreloaderCallback getPreloaderCallback () {
	 * 	return createPreloaderPanel(GWT.getHostPageBaseURL() + "logo_preload.png");
	 * }
	 * </pre>
	 * 
	 * @return PreloaderCallback to use for preload() */
	public PreloaderCallback getPreloaderCallback () {
		return createPreloaderPanel(GWT.getModuleBaseURL() + "logo.png");
	}

	protected PreloaderCallback createPreloaderPanel (String logoUrl) {
		final Panel preloaderPanel = new VerticalPanel();
		preloaderPanel.setStyleName("gdx-preloader");
		final Image logo = new Image(logoUrl);
		logo.setStyleName("logo");
		preloaderPanel.add(logo);
		final Panel meterPanel = new SimplePanel();
		final InlineHTML meter = new InlineHTML();
		final Style meterStyle = meter.getElement().getStyle();
		meterStyle.setWidth(0, Unit.PCT);
		adjustMeterPanel(meterPanel, meterStyle);
		meterPanel.add(meter);
		preloaderPanel.add(meterPanel);
		getRootPanel().add(preloaderPanel);
		return new PreloaderCallback() {

			@Override
			public void error (String file) {
				System.out.println("error: " + file);
			}

			@Override
			public void update (PreloaderState state) {
				meterStyle.setWidth(100f * state.getProgress(), Unit.PCT);
			}

		};
	}

	/** called by {@link #createPreloaderPanel(String)} for overriding purpose. override this method to adjust the styles of the
	 * loading progress bar. Example for changing the bars padding and color:
	 * 
	 * <pre>
	 * meterPanel.setStyleName("gdx-meter");
	 * meterPanel.addStyleName("nostripes");
	 * Style meterPanelStyle = meterPanel.getElement().getStyle();
	 * meterPanelStyle.setProperty("backgroundColor", "#ff0000");
	 * meterPanelStyle.setProperty("padding", "0px");
	 * meterStyle.setProperty("backgroundColor", "#ffffff");
	 * meterStyle.setProperty("backgroundImage", "none");
	 * </pre>
	 */
	protected void adjustMeterPanel (Panel meterPanel, Style meterStyle) {
		meterPanel.setStyleName("gdx-meter");
		meterPanel.addStyleName("red");

	}

	@Override
	public Graphics getGraphics () {
		return graphics;
	}

	@Override
	public Audio getAudio () {
		return audio;
	}

	@Override
	public Input getInput () {
		return Gdx.input;
	}

	@Override
	public Files getFiles () {
		return Gdx.files;
	}

	@Override
	public Net getNet () {
		return Gdx.net;
	}

	private void updateLogLabelSize () {
		if (log != null) {
			if (graphics != null) {
				log.setSize(graphics.getWidth() + "px", "200px");
			} else {
				log.setSize("400px", "200px"); // Should not happen at this point, use dummy value
			}
		}
	}

	@Override
	public void log (String tag, String message) {
		if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message);
	}

	@Override
	public void log (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message, exception);
	}

	@Override
	public void error (String tag, String message) {
		if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message);
	}

	@Override
	public void error (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message, exception);
	}

	@Override
	public void debug (String tag, String message) {
		if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message);
	}

	@Override
	public void debug (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message, exception);
	}

	@Override
	public void setLogLevel (int logLevel) {
		this.logLevel = logLevel;
	}

	@Override
	public int getLogLevel () {
		return logLevel;
	}

	@Override
	public void setApplicationLogger (ApplicationLogger applicationLogger) {
		this.applicationLogger = applicationLogger;
	}

	@Override
	public ApplicationLogger getApplicationLogger () {
		return applicationLogger;
	}

	@Override
	public ApplicationType getType () {
		return ApplicationType.WebGL;
	}

	@Override
	public int getVersion () {
		return 0;
	}

	@Override
	public long getJavaHeap () {
		return 0;
	}

	@Override
	public long getNativeHeap () {
		return 0;
	}

	@Override
	public Preferences getPreferences (String name) {
		Preferences pref = prefs.get(name);
		if (pref == null) {
			pref = new GwtPreferences(name);
			prefs.put(name, pref);
		}
		return pref;
	}

	@Override
	public Clipboard getClipboard () {
		return clipboard;
	}

	@Override
	public void postRunnable (Runnable runnable) {
		runnables.add(runnable);
	}

	@Override
	public void exit () {
	}

	protected GwtAudio createAudio () {
		return new DefaultGwtAudio();
	}

	protected Files createFiles () {
		return new GwtFiles(preloader);
	}

	protected GwtInput createInput (CanvasElement canvas, GwtApplicationConfiguration config) {
		return new DefaultGwtInput(canvas, config);
	}

	/** @return {@code true} if application runs on a mobile device */
	public static boolean isMobileDevice () {
		// RegEx pattern from detectmobilebrowsers.com (public domain)
		String pattern = "(android|bb\\d+|meego).+mobile|avantgo|bada\\/|blackberry|blazer|compal|elaine|fennec"
			+ "|hiptop|iemobile|ip(hone|od)|iris|kindle|lge |maemo|midp|mmp|mobile.+firefox|netfront|opera m(ob|in)"
			+ "i|palm( os)?|phone|p(ixi|re)\\/|plucker|pocket|psp|series(4|6)0|symbian|treo|up\\.(browser|link)"
			+ "|vodafone|wap|windows ce|xda|xiino|android|ipad|playbook|silk";
		Pattern p = Pattern.compile(pattern);
		Matcher m = p.matcher(Window.Navigator.getUserAgent().toLowerCase());
		return m.matches();
	}

	/** Contains precomputed information on the user-agent. Useful for dealing with browser and OS behavioral differences. Kindly
	 * borrowed from PlayN */
	public static AgentInfo agentInfo () {
		return agentInfo;
	}

	/** kindly borrowed from PlayN **/
	private static native AgentInfo computeAgentInfo () /*-{
																			var userAgent = navigator.userAgent.toLowerCase();
																			return {
																			// browser type flags
																			isFirefox : userAgent.indexOf("firefox") != -1,
																			isChrome : userAgent.indexOf("chrome") != -1,
																			isSafari : userAgent.indexOf("safari") != -1,
																			isOpera : userAgent.indexOf("opera") != -1,
																			isIE : userAgent.indexOf("msie") != -1 || userAgent.indexOf("trident") != -1,
																			// OS type flags
																			isMacOS : userAgent.indexOf("mac") != -1,
																			isLinux : userAgent.indexOf("linux") != -1,
																			isWindows : userAgent.indexOf("win") != -1
																			};
																			}-*/;

	/** Returned by {@link #agentInfo}. Kindly borrowed from PlayN. */
	public static class AgentInfo extends JavaScriptObject {
		public final native boolean isFirefox () /*-{
																return this.isFirefox;
																}-*/;

		public final native boolean isChrome () /*-{
																return this.isChrome;
																}-*/;

		public final native boolean isSafari () /*-{
																return this.isSafari;
																}-*/;

		public final native boolean isOpera () /*-{
															return this.isOpera;
															}-*/;

		public final native boolean isIE () /*-{
														return this.isIE;
														}-*/;

		public final native boolean isMacOS () /*-{
															return this.isMacOS;
															}-*/;

		public final native boolean isLinux () /*-{
															return this.isLinux;
															}-*/;

		public final native boolean isWindows () /*-{
																return this.isWindows;
																}-*/;

		protected AgentInfo () {
		}
	}

	public String getBaseUrl () {
		return preloader.baseUrl;
	}

	public Preloader getPreloader () {
		return preloader;
	}

	public CanvasElement getCanvasElement () {
		return graphics.canvas;
	}

	public LoadingListener getLoadingListener () {
		return loadingListener;
	}

	public void setLoadingListener (LoadingListener loadingListener) {
		this.loadingListener = loadingListener;
	}

	@Override
	public void addLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.add(listener);
		}
	}

	@Override
	public void removeLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.removeValue(listener, true);
		}
	}

	native static public void consoleLog (String message) /*-{
		console.log( "GWT: " + message );
	}-*/;

	private native void addEventListeners () /*-{
		var self = this;

		var eventName = null;
		if ("hidden" in $doc) {
			eventName = "visibilitychange"
		} else if ("webkitHidden" in $doc) {
			eventName = "webkitvisibilitychange"
		} else if ("mozHidden" in $doc) {
			eventName = "mozvisibilitychange"
		} else if ("msHidden" in $doc) {
			eventName = "msvisibilitychange"
		}

		if (eventName !== null) {
			$doc.addEventListener(eventName, function(e) {
				self.@com.badlogic.gdx.backends.gwt.GwtApplication::onVisibilityChange(Z)($doc['hidden'] !== true);
			});
		}
	}-*/;

	private void onVisibilityChange (boolean visible) {
		if (visible) {
			for (LifecycleListener listener : lifecycleListeners) {
				listener.resume();
			}
			listener.resume();
		} else {
			for (LifecycleListener listener : lifecycleListeners) {
				listener.pause();
			}
			listener.pause();
		}
	}

	/** LoadingListener interface main purpose is to do some things before or after {@link GwtApplication#setupLoop()} */
	public interface LoadingListener {
		/** Method called before the setup */
		public void beforeSetup ();

		/** Method called after the setup */
		public void afterSetup ();
	}

	/** ResizeListener called when browser window is resized */
	class ResizeListener implements ResizeHandler {
		@Override
		public void onResize (ResizeEvent event) {

			int width = event.getWidth() - config.padHorizontal;
			int height = event.getHeight() - config.padVertical;

			// ignore 0, 0
			if (width == 0 || height == 0) {
				return;
			}

			getRootPanel().setWidth("" + width + "px");
			getRootPanel().setHeight("" + height + "px");

			// while preloading and before setupLoop() is called, graphics are not initialized
			if (graphics != null) {
				// event calls us with logical pixel size, so if we use physical pixels internally,
				// we need to convert them
				if (config.usePhysicalPixels) {
					double density = GwtGraphics.getNativeScreenDensity();
					width = (int)(width * density);
					height = (int)(height * density);
				}
				graphics.setCanvasSize(width, height);

			}
		}
	}
}
