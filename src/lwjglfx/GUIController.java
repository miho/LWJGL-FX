/*
 * Copyright (c) 2002-2012 LWJGL Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'LWJGL' nor the names of
 *   its contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package lwjglfx;

import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyFloatProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.*;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.SnapshotParameters;
import javafx.scene.SnapshotResult;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebEvent;
import javafx.scene.web.WebView;
import javafx.util.Callback;
import javafx.util.Duration;

import static java.lang.Math.*;
import static javafx.beans.binding.Bindings.*;
import static javafx.collections.FXCollections.*;
import static lwjglfx.StreamPBOReader.*;
import static lwjglfx.StreamPBOWriter.*;

/** The JavaFX application GUI controller. */
public class GUIController implements Initializable {

	@FXML private AnchorPane gearsRoot;
	@FXML private ImageView  gearsView;
	@FXML private Label      fpsLabel;

	@FXML private CheckBox vsync;
	@FXML private ChoiceBox<BufferingChoice>
	                       bufferingChoice;
	@FXML private Slider   msaaSamples;

	@FXML private WebView webView;

	private WritableImage renderImage;
	private WritableImage webImage;

	private Gears gears;

	public GUIController() {
	}

	public void initialize(final URL url, final ResourceBundle resourceBundle) {
		gearsView.fitWidthProperty().bind(gearsRoot.widthProperty());
		gearsView.fitHeightProperty().bind(gearsRoot.heightProperty());

		bufferingChoice.setItems(observableArrayList(BufferingChoice.values()));
		bufferingChoice.getSelectionModel().select(BufferingChoice.DOUBLE);
	}

	private ReadHandler getReadHandler() {
		return new ReadHandler() {

			public int getWidth() {
				return (int)gearsView.getFitWidth();
			}

			public int getHeight() {
				return (int)gearsView.getFitHeight();
			}

			public void process(final int width, final int height, final ByteBuffer data, final CountDownLatch signal) {
				// This method runs in the background rendering thread
				Platform.runLater(new Runnable() {
					public void run() {
						// If we're quitting, discard update
						if ( !gearsView.isVisible() ) {
							signal.countDown();
							return;
						}

						// Detect resize and recreate the image
						if ( renderImage == null || (int)renderImage.getWidth() != width || (int)renderImage.getHeight() != height ) {
							renderImage = new WritableImage(width, height);
							gearsView.setImage(renderImage);
						}

						// Upload the image to JavaFX
						renderImage.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(), data, width * 4);

						// Notify the render thread that we're done processing
						signal.countDown();
					}
				});
			}
		};
	}

	private WriteHandler getWriteHandler() {
		return new WriteHandler() {
			public int getWidth() {
				return (int)webView.getWidth();
			}

			public int getHeight() {
				return (int)webView.getHeight();
			}

			public void process(final int width, final int height, final ByteBuffer buffer, final CountDownLatch signal) {
				// This method runs in the background rendering thread
				Platform.runLater(new Runnable() {
					public void run() {
						if ( webImage == null || webImage.getWidth() != width || webImage.getHeight() != height )
							webImage = new WritableImage(width, height);

						webView.snapshot(new Callback<SnapshotResult, Void>() {
							public Void call(final SnapshotResult snapshotResult) {
								snapshotResult.getImage().getPixelReader().getPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(), buffer, width * 4);
								signal.countDown();
								return null;

							}
						}, new SnapshotParameters(), webImage);
					}
				});
			}
		};
	}

	// This method will run in the background rendering thread
	void runGears(final CountDownLatch runningLatch) {
		try {
			gears = new Gears(
				getReadHandler(),
				getWriteHandler()
			);
		} catch (Throwable t) {
			t.printStackTrace();
			return;
		}

		Platform.runLater(new Runnable() {
			public void run() {
				// Listen for FPS changes and update the fps label
				final ReadOnlyFloatProperty fps = gears.fpsProperty();

				fpsLabel.textProperty().bind(createStringBinding(new Callable<String>() {
					public String call() throws Exception {
						return "FPS: " + fps.get();
					}
				}, fps));

				vsync.selectedProperty().addListener(new ChangeListener<Boolean>() {
					public void changed(final ObservableValue<? extends Boolean> observableValue, final Boolean oldValue, final Boolean newValue) {
						gears.setVsync(newValue);
					}
				});

				bufferingChoice.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<BufferingChoice>() {
					public void changed(final ObservableValue<? extends BufferingChoice> observableValue, final BufferingChoice oldValue, final BufferingChoice newValue) {
						gears.setTransfersToBuffer(newValue.getTransfersToBuffer());
					}
				});

				final int maxSamples = gears.getMaxSamples();
				if ( maxSamples == 1 )
					msaaSamples.setDisable(true);
				else {
					msaaSamples.setMax(maxSamples);
					msaaSamples.valueProperty().addListener(new ChangeListener<Number>() {

						public boolean isPoT(final int value) {
							return value != 0 && (value & (value - 1)) == 0;
						}

						public int nextPoT(final int value) {
							int v = value - 1;

							v |= (v >>> 1);
							v |= (v >>> 2);
							v |= (v >>> 4);
							v |= (v >>> 8);
							v |= (v >>> 16);

							return v + 1;
						}

						public void changed(final ObservableValue<? extends Number> observableValue, final Number oldValue, final Number newValue) {
							final float value = newValue.floatValue();
							final int samples = round(value);

							if ( isPoT(samples) )
								gears.setSamples(samples);
							else {
								// Snap to powers of two
								final int nextPoT = nextPoT(samples);
								final int prevPoT = nextPoT >> 1;

								msaaSamples.setValue(
									value - prevPoT < nextPoT - value
									? prevPoT
									: nextPoT
								);
							}
						}
					});
				}

				// Listen for changes to the WebView contents.
				final ChangeListener<Number> numberListener = new ChangeListener<Number>() {
					public void changed(final ObservableValue<? extends Number> observableValue, final Number oldValue, final Number newValue) {
						gears.updateSnapshot();
					}
				};

				webView.widthProperty().addListener(numberListener);
				webView.heightProperty().addListener(numberListener);

				final WebEngine engine = webView.getEngine();

				engine.getLoadWorker().progressProperty().addListener(numberListener);
				engine.setOnStatusChanged(new EventHandler<WebEvent<String>>() {
					public void handle(final WebEvent<String> e) {
						gears.updateSnapshot();
					}
				});

				webView.setEventDispatcher(new EventDispatcher() {
					private final EventDispatcher parent = webView.getEventDispatcher();

					public Event dispatchEvent(final Event e, final EventDispatchChain dispatchChain) {
						// Mouse over events within the page will be triggered by the StatusChanged handler above.
						if ( e.getEventType() != MouseEvent.MOUSE_MOVED && gears != null )
							gears.updateSnapshot();

						return parent.dispatchEvent(e, dispatchChain);
					}
				});

				// Force an update every 4 frames for carets.
				final Timeline timeline = new Timeline();
				timeline.setCycleCount(Timeline.INDEFINITE);
				timeline.setAutoReverse(true);
				timeline.getKeyFrames().add(new KeyFrame(Duration.millis(4 * (1000 / 60)), new EventHandler<ActionEvent>() {
					public void handle(final ActionEvent e) {
						if ( webView.isFocused() )
							gears.updateSnapshot();
					}
				}));
				timeline.play();

				// Do one last update on focus lost
				webView.focusedProperty().addListener(new ChangeListener<Boolean>() {
					public void changed(final ObservableValue<? extends Boolean> observableValue, final Boolean oldValue, final Boolean newValue) {
						if ( !newValue )
							gears.updateSnapshot();
					}
				});

				webView.getEngine().load("http://www.javagaming.org");
			}
		});

		gears.execute(runningLatch);
	}

	private enum BufferingChoice {
		SINGLE(1, "No buffering"),
		DOUBLE(2, "Double buffering"),
		TRIPLE(3, "Triple buffering");

		private final int    transfersToBuffer;
		private final String description;

		private BufferingChoice(final int transfersToBuffer, final String description) {
			this.transfersToBuffer = transfersToBuffer;
			this.description = transfersToBuffer + "x - " + description;
		}

		public int getTransfersToBuffer() {
			return transfersToBuffer;
		}

		public String getDescription() {
			return description;
		}

		public String toString() {
			return description;
		}
	}

}