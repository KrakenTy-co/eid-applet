/*
 * eID Applet Project.
 * Copyright (C) 2008-2009 FedICT.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, see 
 * http://www.gnu.org/licenses/.
 */

package be.fedict.eid.applet;

import java.awt.Component;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import sun.security.pkcs11.wrapper.PKCS11Exception;
import be.fedict.eid.applet.Messages.MESSAGE_ID;
import be.fedict.eid.applet.shared.AdministrationMessage;
import be.fedict.eid.applet.shared.AppletProtocolMessageCatalog;
import be.fedict.eid.applet.shared.AuthenticationContract;
import be.fedict.eid.applet.shared.AuthenticationDataMessage;
import be.fedict.eid.applet.shared.AuthenticationRequestMessage;
import be.fedict.eid.applet.shared.CheckClientMessage;
import be.fedict.eid.applet.shared.ClientEnvironmentMessage;
import be.fedict.eid.applet.shared.ContinueInsecureMessage;
import be.fedict.eid.applet.shared.FileDigestsDataMessage;
import be.fedict.eid.applet.shared.FilesDigestRequestMessage;
import be.fedict.eid.applet.shared.FinishedMessage;
import be.fedict.eid.applet.shared.HelloMessage;
import be.fedict.eid.applet.shared.IdentificationRequestMessage;
import be.fedict.eid.applet.shared.IdentityDataMessage;
import be.fedict.eid.applet.shared.InsecureClientMessage;
import be.fedict.eid.applet.shared.KioskMessage;
import be.fedict.eid.applet.shared.SignRequestMessage;
import be.fedict.eid.applet.shared.SignatureDataMessage;
import be.fedict.eid.applet.shared.annotation.ResponsesAllowed;
import be.fedict.eid.applet.shared.protocol.ProtocolContext;
import be.fedict.eid.applet.shared.protocol.ProtocolStateMachine;
import be.fedict.eid.applet.shared.protocol.Transport;
import be.fedict.eid.applet.shared.protocol.Unmarshaller;

/**
 * Controller component. Contains the eID logic. Interacts with {@link View} and
 * {@link Runtime} for outside world communication.
 * 
 * @author fcorneli
 * 
 */
public class Controller {

	private final View view;

	private final Runtime runtime;

	private final Messages messages;

	/**
	 * Will only be set if PC/SC Java 6 is available.
	 */
	private final PcscEidSpi pcscEidSpi;

	private final Pkcs11Eid pkcs11Eid;

	private final ProtocolStateMachine protocolStateMachine;

	@SuppressWarnings("unchecked")
	public Controller(View view, Runtime runtime, Messages messages) {
		this.view = view;
		this.runtime = runtime;
		this.messages = messages;
		if (false == System.getProperty("java.version").startsWith("1.5")) {
			/*
			 * Java 1.6 and later. Loading the PcscEid class via reflection
			 * avoids a hard reference to Java 1.6 specific code. This is
			 * required in order to be able to let a Java 1.5 runtime load this
			 * Controller class without exploding on unsupported 1.6 types.
			 */
			try {
				Class<? extends PcscEidSpi> pcscEidClass = (Class<? extends PcscEidSpi>) Class
						.forName("be.fedict.eid.applet.PcscEid");
				Constructor<? extends PcscEidSpi> pcscEidConstructor = pcscEidClass
						.getConstructor(View.class, Messages.class);
				this.pcscEidSpi = pcscEidConstructor.newInstance(this.view,
						this.messages);
			} catch (Exception e) {
				throw new RuntimeException("Error loading pcsc eid component: "
						+ e.getMessage());
			}
			this.pcscEidSpi.addObserver(new PcscEidObserver());
		} else {
			/*
			 * No PC/SC Java 6 available.
			 */
			this.pcscEidSpi = null;
		}
		this.pkcs11Eid = new Pkcs11Eid(this.view, this.messages);
		ProtocolContext protocolContext = new LocalAppletProtocolContext(
				this.view);
		this.protocolStateMachine = new ProtocolStateMachine(protocolContext);
	}

	private <T> T sendMessage(Object message, Class<T> responseClass)
			throws MalformedURLException, IOException {
		Object responseObject = sendMessage(message);
		if (false == responseClass.equals(responseObject.getClass())) {
			throw new RuntimeException("response message not of type: "
					+ responseClass.getName());
		}
		@SuppressWarnings("unchecked")
		T response = (T) responseObject;
		return response;
	}

	private Object sendMessage(Object message) throws MalformedURLException,
			IOException {
		addDetailMessage("sending message: "
				+ message.getClass().getSimpleName());
		Class<?> messageClass = message.getClass();
		ResponsesAllowed responsesAllowedAnnotation = messageClass
				.getAnnotation(ResponsesAllowed.class);
		if (null == responsesAllowedAnnotation) {
			throw new RuntimeException(
					"message should have a @ResponsesAllowed constraint");
		}

		this.protocolStateMachine.checkRequestMessage(message);

		HttpURLConnection connection = getServerConnection();
		HttpURLConnectionHttpTransmitter httpTransmitter = new HttpURLConnectionHttpTransmitter(
				connection);
		Transport.transfer(message, httpTransmitter);
		int responseCode = connection.getResponseCode();
		if (HttpURLConnection.HTTP_OK != responseCode) {
			String msg;
			if (HttpURLConnection.HTTP_NOT_FOUND == responseCode) {
				msg = "HTTP NOT FOUND! eID Applet Service not running?";
			} else {
				msg = Integer.toString(responseCode);
			}
			this.view.addDetailMessage("HTTP response code: " + msg);
			throw new IOException(
					"error sending message to service. HTTP status code: "
							+ msg);
		}
		Unmarshaller unmarshaller = new Unmarshaller(
				new AppletProtocolMessageCatalog());
		HttpURLConnectionHttpReceiver httpReceiver = new HttpURLConnectionHttpReceiver(
				connection);
		Object responseObject = unmarshaller.receive(httpReceiver);

		Class<?>[] responsesAllowed = responsesAllowedAnnotation.value();
		if (false == isOfClass(responseObject, responsesAllowed)) {
			throw new RuntimeException("response not of correct type: "
					+ responseObject.getClass());
		}
		addDetailMessage("response message: "
				+ responseObject.getClass().getSimpleName());

		this.protocolStateMachine.checkResponseMessage(responseObject);

		return responseObject;
	}

	private boolean isOfClass(Object object, Class<?>[] classes) {
		for (Class<?> clazz : classes) {
			if (clazz.equals(object.getClass())) {
				return true;
			}
		}
		return false;
	}

	public Object run() {
		printEnvironment();

		try {
			HelloMessage helloMessage = new HelloMessage();
			Object resultMessage = sendMessage(helloMessage);
			if (resultMessage instanceof CheckClientMessage) {
				addDetailMessage("Need to check the client secure environment...");
				ClientEnvironmentMessage clientEnvMessage = new ClientEnvironmentMessage();
				clientEnvMessage.javaVersion = System
						.getProperty("java.version");
				clientEnvMessage.javaVendor = System.getProperty("java.vendor");
				clientEnvMessage.osName = System.getProperty("os.name");
				clientEnvMessage.osArch = System.getProperty("os.arch");
				clientEnvMessage.osVersion = System.getProperty("os.version");
				if (null != this.pcscEidSpi) {
					/*
					 * First we try it via PC/SC Java 6.
					 */
					clientEnvMessage.readerList = this.pcscEidSpi
							.getReaderList();
				} else {
					/*
					 * Get the reader list via the PKCS#11 library.
					 */
					clientEnvMessage.readerList = this.pkcs11Eid
							.getReaderList();
				}
				clientEnvMessage.navigatorAppName = this.runtime
						.getParameter("NavigatorAppName");
				clientEnvMessage.navigatorAppVersion = this.runtime
						.getParameter("NavigatorAppVersion");
				clientEnvMessage.navigatorUserAgent = this.runtime
						.getParameter("NavigatorUserAgent");
				resultMessage = sendMessage(clientEnvMessage);
				if (resultMessage instanceof InsecureClientMessage) {
					InsecureClientMessage insecureClientMessage = (InsecureClientMessage) resultMessage;
					if (insecureClientMessage.warnOnly) {
						int result = JOptionPane
								.showConfirmDialog(
										this.view.getParentComponent(),
										"Your system has been marked as insecure client environment.\n"
												+ "Do you want to continue the eID operation?",
										"Insecure Client Environment",
										JOptionPane.OK_CANCEL_OPTION,
										JOptionPane.WARNING_MESSAGE);
						if (JOptionPane.OK_OPTION != result) {
							setStatusMessage(
									Status.ERROR,
									Controller.this.messages
											.getMessage(MESSAGE_ID.SECURITY_ERROR));
							addDetailMessage("insecure client environment");
							return null;
						}
						resultMessage = sendMessage(new ContinueInsecureMessage());
					} else {
						JOptionPane
								.showMessageDialog(
										this.view.getParentComponent(),
										"Your system has been marked as insecure client environment.",
										"Insecure Client Environment",
										JOptionPane.ERROR_MESSAGE);
						setStatusMessage(Status.ERROR, Controller.this.messages
								.getMessage(MESSAGE_ID.SECURITY_ERROR));
						addDetailMessage("received an insecure client environment message");
						return null;
					}
				}
			}
			if (resultMessage instanceof KioskMessage) {
				kioskMode();
			}
			if (resultMessage instanceof AdministrationMessage) {
				AdministrationMessage administrationMessage = (AdministrationMessage) resultMessage;
				boolean changePin = administrationMessage.changePin;
				boolean unblockPin = administrationMessage.unblockPin;
				boolean removeCard = administrationMessage.removeCard;
				boolean logoff = administrationMessage.logoff;
				addDetailMessage("change pin: " + changePin);
				addDetailMessage("unblock pin: " + unblockPin);
				addDetailMessage("remove card: " + removeCard);
				addDetailMessage("logoff: " + logoff);
				administration(unblockPin, changePin, logoff, removeCard);
			}
			if (resultMessage instanceof FilesDigestRequestMessage) {
				FilesDigestRequestMessage filesDigestRequestMessage = (FilesDigestRequestMessage) resultMessage;
				resultMessage = performFilesDigestOperation(filesDigestRequestMessage.digestAlgo);
			}
			if (resultMessage instanceof SignRequestMessage) {
				SignRequestMessage signRequestMessage = (SignRequestMessage) resultMessage;
				performEidSignOperation(signRequestMessage);
			}
			if (resultMessage instanceof AuthenticationRequestMessage) {
				AuthenticationRequestMessage authnRequest = (AuthenticationRequestMessage) resultMessage;
				performEidAuthnOperation(authnRequest);
			}
			if (resultMessage instanceof IdentificationRequestMessage) {
				IdentificationRequestMessage identificationRequestMessage = (IdentificationRequestMessage) resultMessage;
				addDetailMessage("include address: "
						+ identificationRequestMessage.includeAddress);
				addDetailMessage("include photo: "
						+ identificationRequestMessage.includePhoto);
				addDetailMessage("include integrity data: "
						+ identificationRequestMessage.includeIntegrityData);
				addDetailMessage("include certificates: "
						+ identificationRequestMessage.includeCertificates);
				addDetailMessage("remove card: "
						+ identificationRequestMessage.removeCard);

				performEidIdentificationOperation(
						identificationRequestMessage.includeAddress,
						identificationRequestMessage.includePhoto,
						identificationRequestMessage.includeIntegrityData,
						identificationRequestMessage.includeCertificates,
						identificationRequestMessage.removeCard);
			}
		} catch (PKCS11NotFoundException e) {
			setStatusMessage(Status.ERROR, Controller.this.messages
					.getMessage(MESSAGE_ID.NO_MIDDLEWARE_ERROR));
			addDetailMessage("error: no eID Middleware PKCS#11 library found");
			return null;
		} catch (SecurityException e) {
			setStatusMessage(Status.ERROR, Controller.this.messages
					.getMessage(MESSAGE_ID.SECURITY_ERROR));
			addDetailMessage("error: " + e.getMessage());
			return null;
		} catch (Exception e) {
			addDetailMessage("error: " + e.getMessage());
			addDetailMessage("error type: " + e.getClass().getName());
			StackTraceElement[] stackTrace = e.getStackTrace();
			for (StackTraceElement stackTraceElement : stackTrace) {
				addDetailMessage("at " + stackTraceElement.getClassName() + "."
						+ stackTraceElement.getMethodName() + ":"
						+ stackTraceElement.getLineNumber());
			}
			Throwable cause = e.getCause();
			if (null != cause) {
				addDetailMessage("Caused by: " + cause.getClass().getName()
						+ ": " + cause.getMessage());
				stackTrace = cause.getStackTrace();
				for (StackTraceElement stackTraceElement : stackTrace) {
					addDetailMessage("at " + stackTraceElement.getClassName()
							+ "." + stackTraceElement.getMethodName() + ":"
							+ stackTraceElement.getLineNumber());
				}
			}
			/*
			 * We don't refer to javax.smartcardio directly since this code also
			 * need to work an a Java 5 runtime.
			 */
			if ("javax.smartcardio.CardException"
					.equals(e.getClass().getName())) {
				setStatusMessage(Status.ERROR, Controller.this.messages
						.getMessage(MESSAGE_ID.CARD_ERROR));
				addDetailMessage("card error: " + e.getMessage());
				return null;
			}
			setStatusMessage(Status.ERROR, Controller.this.messages
					.getMessage(MESSAGE_ID.GENERIC_ERROR));
			return null;
		}

		setStatusMessage(Status.NORMAL, Controller.this.messages
				.getMessage(MESSAGE_ID.DONE));
		this.runtime.gotoTargetPage();
		return null;
	}

	private void kioskMode() throws IllegalArgumentException,
			SecurityException, IOException, PKCS11Exception,
			InterruptedException, NoSuchFieldException, IllegalAccessException,
			InvocationTargetException, NoSuchMethodException, Exception {
		addDetailMessage("entering Kiosk Mode...");
		this.view.setStatusMessage(Status.NORMAL, "Kiosk Mode...");
		while (true) {
			if (false == this.pkcs11Eid.isEidPresent()) {
				this.pkcs11Eid.waitForEidPresent();
			}
			addDetailMessage("waiting for card removal...");
			this.pkcs11Eid.removeCard();
			addDetailMessage("card removed");
			ClassLoader classLoader = Controller.class.getClassLoader();
			Class<?> jsObjectClass;
			try {
				jsObjectClass = classLoader
						.loadClass("netscape.javascript.JSObject");
			} catch (ClassNotFoundException e) {
				addDetailMessage("JSObject class not found");
				addDetailMessage("not running inside a browser?");
				continue;
			}
			Method getWindowMethod = jsObjectClass.getMethod("getWindow",
					new Class<?>[] { java.applet.Applet.class });
			Object jsObject = getWindowMethod.invoke(null, this.runtime
					.getApplet());
			Method callMethod = jsObjectClass.getMethod("call", new Class<?>[] {
					String.class, Class.forName("[Ljava.lang.Object;") });
			String removeCardCallback = this.runtime
					.getParameter("RemoveCardCallback");
			if (null != removeCardCallback) {
				addDetailMessage("invoking javascript callback: "
						+ removeCardCallback);
				callMethod
						.invoke(jsObject, removeCardCallback, new Object[] {});
			} else {
				addDetailMessage("missing RemoveCardCallback parameter");
			}
		}
	}

	/**
	 * We're not accepting MD5.
	 */
	private final String[] SUPPORTED_FILES_DIGEST_ALGOS = new String[] {
			"SHA1", "SHA-1", "SHA-256", "SHA-384", "SHA-512" };

	private Object performFilesDigestOperation(String filesDigestAlgo)
			throws NoSuchAlgorithmException, IOException {
		addDetailMessage("files digest algorithm: " + filesDigestAlgo);

		boolean isSupportedFilesDigestAlgo = false;
		for (String supportedFilesDigestAlgo : SUPPORTED_FILES_DIGEST_ALGOS) {
			if (supportedFilesDigestAlgo.equals(filesDigestAlgo)) {
				isSupportedFilesDigestAlgo = true;
				break;
			}
		}
		if (false == isSupportedFilesDigestAlgo) {
			throw new SecurityException("files digest algo not supported: "
					+ filesDigestAlgo);
		}

		MessageDigest messageDigest = MessageDigest
				.getInstance(filesDigestAlgo);

		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.SELECT_FILES));
		JFileChooser fileChooser = new JFileChooser();
		fileChooser.setMultiSelectionEnabled(true);
		int returnCode = fileChooser.showDialog(getParentComponent(),
				this.messages.getMessage(MESSAGE_ID.SELECT_FILES));
		if (JFileChooser.APPROVE_OPTION != returnCode) {
			throw new RuntimeException("file selection aborted");
		}

		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.DIGESTING_FILES));

		FileDigestsDataMessage fileDigestsDataMessage = new FileDigestsDataMessage();
		fileDigestsDataMessage.fileDigestInfos = new LinkedList<String>();
		File[] selectedFiles = fileChooser.getSelectedFiles();
		long totalSize = 0;
		for (File selectedFile : selectedFiles) {
			totalSize += selectedFile.length();
		}
		final int BUFFER_SIZE = 1024 * 10;
		int progressMax = (int) (totalSize / BUFFER_SIZE);
		this.view.progressIndication(progressMax, 0);
		addDetailMessage("total data size to digest: " + (totalSize / 1024)
				+ " KiB");
		int progress = 0;
		for (File selectedFile : selectedFiles) {
			fileDigestsDataMessage.fileDigestInfos.add(filesDigestAlgo);
			long fileSize = selectedFile.length();
			addDetailMessage(selectedFile.getAbsolutePath() + ": "
					+ (fileSize / 1024) + " KiB");
			FileInputStream fileInputStream = new FileInputStream(selectedFile);
			DigestInputStream digestInputStream = new DigestInputStream(
					fileInputStream, messageDigest);
			byte[] buffer = new byte[BUFFER_SIZE];
			while (-1 != digestInputStream.read(buffer)) {
				progress++;
				this.view.progressIndication(progressMax, progress);
			}
			digestInputStream.close();
			byte[] fileDigestValue = messageDigest.digest();
			messageDigest.reset();
			String fileDigest = toHex(fileDigestValue);
			fileDigestsDataMessage.fileDigestInfos.add(fileDigest);
			fileDigestsDataMessage.fileDigestInfos.add(selectedFile.getName());
		}
		this.view.progressIndication(-1, 0);
		Object resultMessage = sendMessage(fileDigestsDataMessage);
		return resultMessage;
	}

	public static String toHex(byte[] data) {
		StringBuffer stringBuffer = new StringBuffer();
		for (byte b : data) {
			stringBuffer.append(toHex(b >> 4));
			stringBuffer.append(toHex(b));
		}
		return stringBuffer.toString();
	}

	private static char toHex(int value) {
		value &= 0xf;
		switch (value) {
		case 10:
			return 'A';
		case 11:
			return 'B';
		case 12:
			return 'C';
		case 13:
			return 'D';
		case 14:
			return 'E';
		case 15:
			return 'F';
		default:
			return (char) ('0' + value);
		}

	}

	private void performEidSignOperation(SignRequestMessage signRequestMessage)
			throws Exception {
		boolean logoff = signRequestMessage.logoff;
		boolean removeCard = signRequestMessage.removeCard;
		addDetailMessage("logoff: " + logoff);
		addDetailMessage("remove card: " + removeCard);
		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.DETECTING_CARD));
		try {
			if (false == this.pkcs11Eid.isEidPresent()) {
				setStatusMessage(Status.NORMAL, this.messages
						.getMessage(MESSAGE_ID.INSERT_CARD_QUESTION));
				this.pkcs11Eid.waitForEidPresent();
			}
		} catch (PKCS11NotFoundException e) {
			addDetailMessage("eID Middleware PKCS#11 library not found.");
			if (null != this.pcscEidSpi) {
				addDetailMessage("fallback to PC/SC signing...");
				performEidPcscSignOperation(signRequestMessage);
				return;
			}
			throw new PKCS11NotFoundException();
		}
		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.SIGNING));

		String logoffReaderName;
		if (logoff) {
			if (null == this.pcscEidSpi) {
				/*
				 * We cannot logoff via PC/SC so we remove the card via PKCS#11.
				 */
				removeCard = true;
				logoffReaderName = null;
			} else {
				if (removeCard) {
					/*
					 * No need to logoff a removed card.
					 */
					logoffReaderName = null;
				} else {
					logoffReaderName = this.pkcs11Eid.getSlotDescription();
				}
			}
		} else {
			logoffReaderName = null;
		}

		byte[] signatureValue;
		List<X509Certificate> signCertChain;
		try {
			/*
			 * Via next dialog we try to implement WYSIWYS using the digest
			 * description. Also showing the digest value is pointless.
			 */
			// TODO DRY refactoring
			int response = JOptionPane.showConfirmDialog(this
					.getParentComponent(), "OK to sign \""
					+ signRequestMessage.description + "\"?\n"
					+ "Signature algorithm: " + signRequestMessage.digestAlgo
					+ " with RSA", "Signature creation",
					JOptionPane.YES_NO_OPTION);
			// TODO i18n
			if (JOptionPane.OK_OPTION != response) {
				throw new SecurityException("sign operation aborted");
			}
			addDetailMessage("signing with algorithm: "
					+ signRequestMessage.digestAlgo + " with RSA");
			signatureValue = this.pkcs11Eid.sign(
					signRequestMessage.digestValue,
					signRequestMessage.digestAlgo);
			signCertChain = this.pkcs11Eid.getSignCertificateChain();
			if (removeCard) {
				setStatusMessage(Status.NORMAL, this.messages
						.getMessage(MESSAGE_ID.REMOVE_CARD));
				this.pkcs11Eid.removeCard();
			}
		} finally {
			/*
			 * We really need to remove the SunPKCS11 security provider, else a
			 * following eID signature will fail.
			 */
			this.pkcs11Eid.close();
			if (null != logoffReaderName) {
				this.pcscEidSpi.logoff(logoffReaderName);
			}
		}
		SignatureDataMessage signatureDataMessage = new SignatureDataMessage(
				signatureValue, signCertChain);
		Object responseMessage = sendMessage(signatureDataMessage);
		if (false == (responseMessage instanceof FinishedMessage)) {
			throw new RuntimeException("finish expected");
		}
	}

	private void performEidPcscSignOperation(
			SignRequestMessage signRequestMessage) throws Exception {
		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.DETECTING_CARD));
		if (false == this.pcscEidSpi.isEidPresent()) {
			setStatusMessage(Status.NORMAL, this.messages
					.getMessage(MESSAGE_ID.INSERT_CARD_QUESTION));
			this.pcscEidSpi.waitForEidPresent();
		}

		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.SIGNING));
		byte[] signatureValue;
		List<X509Certificate> signCertChain;
		try {
			/*
			 * Via next dialog we try to implement WYSIWYS using the digest
			 * description. Also showing the digest value is pointless.
			 */
			// TODO DRY refactoring
			int response = JOptionPane.showConfirmDialog(this
					.getParentComponent(), "OK to sign \""
					+ signRequestMessage.description + "\"?\n"
					+ "Signature algorithm: " + signRequestMessage.digestAlgo
					+ " with RSA", "Signature creation",
					JOptionPane.YES_NO_OPTION);
			// TODO i18n
			if (JOptionPane.OK_OPTION != response) {
				throw new SecurityException("sign operation aborted");
			}
			signatureValue = this.pcscEidSpi.sign(
					signRequestMessage.digestValue,
					signRequestMessage.digestAlgo);
			signCertChain = this.pcscEidSpi.getSignCertificateChain();
			if (signRequestMessage.logoff && !signRequestMessage.removeCard) {
				this.pcscEidSpi.logoff();
			}
			if (signRequestMessage.removeCard) {
				setStatusMessage(Status.NORMAL, this.messages
						.getMessage(MESSAGE_ID.REMOVE_CARD));
				this.pcscEidSpi.removeCard();
			}
		} finally {
			this.pcscEidSpi.close();
		}

		SignatureDataMessage signatureDataMessage = new SignatureDataMessage(
				signatureValue, signCertChain);
		Object responseMessage = sendMessage(signatureDataMessage);
		if (false == (responseMessage instanceof FinishedMessage)) {
			throw new RuntimeException("finish expected");
		}
	}

	private void administration(boolean unblockPin, boolean changePin,
			boolean logoff, boolean removeCard) throws Exception {
		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.DETECTING_CARD));
		if (false == this.pcscEidSpi.isEidPresent()) {
			setStatusMessage(Status.NORMAL, this.messages
					.getMessage(MESSAGE_ID.INSERT_CARD_QUESTION));
			this.pcscEidSpi.waitForEidPresent();
		}
		try {
			if (unblockPin) {
				setStatusMessage(Status.NORMAL, "Unblock PIN...");
				this.pcscEidSpi.unblockPin();
			}
			if (changePin) {
				setStatusMessage(Status.NORMAL, "Change PIN...");
				this.pcscEidSpi.changePin();
			}
			if (logoff) {
				this.pcscEidSpi.logoff();
			}
			if (removeCard) {
				setStatusMessage(Status.NORMAL, this.messages
						.getMessage(MESSAGE_ID.REMOVE_CARD));
				this.pcscEidSpi.removeCard();
			}
		} finally {
			/*
			 * Required to release the exclusive access lock. Else a next
			 * execution of the eID Applet will fail.
			 */
			this.pcscEidSpi.close();
		}
	}

	private void performEidAuthnOperation(
			AuthenticationRequestMessage authnRequest) throws Exception {
		byte[] challenge = authnRequest.challenge;
		boolean removeCard = authnRequest.removeCard;
		boolean includeHostname = authnRequest.includeHostname;
		boolean includeInetAddress = authnRequest.includeInetAddress;
		boolean logoff = authnRequest.logoff;
		if (challenge.length < 20) {
			throw new SecurityException(
					"challenge should be at least 20 bytes long.");
		}
		addDetailMessage("include hostname: " + includeHostname);
		addDetailMessage("include inet address: " + includeInetAddress);
		addDetailMessage("remove card after authn: " + removeCard);
		addDetailMessage("logoff: " + logoff);

		SecureRandom secureRandom = new SecureRandom();
		byte[] salt = new byte[20];
		secureRandom.nextBytes(salt);

		String hostname;
		if (includeHostname) {
			/*
			 * We extract the hostname from the web page location in which this
			 * eID Applet is embedded.
			 */
			URL documentBase = this.runtime.getDocumentBase();
			hostname = documentBase.getHost();
			addDetailMessage("hostname: " + hostname);
		} else {
			hostname = null;
		}

		InetAddress inetAddress;
		if (includeInetAddress) {
			URL documentBase = this.runtime.getDocumentBase();
			inetAddress = InetAddress.getByName(documentBase.getHost());
			addDetailMessage("inet address: " + inetAddress.getHostAddress());
		} else {
			inetAddress = null;
		}

		byte[] sessionId = AppletSSLSocketFactory.getActualSessionId();

		AuthenticationContract authenticationContract = new AuthenticationContract(
				salt, hostname, inetAddress, sessionId, challenge);
		byte[] toBeSigned = authenticationContract.calculateToBeSigned();

		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.DETECTING_CARD));
		try {
			if (false == this.pkcs11Eid.isEidPresent()) {
				setStatusMessage(Status.NORMAL, this.messages
						.getMessage(MESSAGE_ID.INSERT_CARD_QUESTION));
				this.pkcs11Eid.waitForEidPresent();
			}
		} catch (PKCS11NotFoundException e) {
			addDetailMessage("eID Middleware PKCS#11 library not found.");
			if (null != this.pcscEidSpi) {
				addDetailMessage("fallback to PC/SC interface for authentication...");
				performEidPcscAuthnOperation(salt, sessionId, toBeSigned,
						logoff, removeCard);
				return;
			}
			throw new PKCS11NotFoundException();
		}
		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.AUTHENTICATING));

		String logoffReaderName;
		if (logoff) {
			if (null == this.pcscEidSpi) {
				/*
				 * We cannot logoff via PC/SC so we remove the card via PKCS#11.
				 */
				removeCard = true;
				logoffReaderName = null;
			} else {
				if (removeCard) {
					/*
					 * No need to logoff a removed card.
					 */
					logoffReaderName = null;
				} else {
					logoffReaderName = this.pkcs11Eid.getSlotDescription();
				}
			}
		} else {
			logoffReaderName = null;
		}

		byte[] signatureValue;
		List<X509Certificate> authnCertChain;
		try {
			signatureValue = this.pkcs11Eid.signAuthn(toBeSigned);
			authnCertChain = this.pkcs11Eid.getAuthnCertificateChain();
			if (removeCard) {
				setStatusMessage(Status.NORMAL, this.messages
						.getMessage(MESSAGE_ID.REMOVE_CARD));
				this.pkcs11Eid.removeCard();
			}
		} finally {
			/*
			 * We really need to remove the SunPKCS11 security provider, else a
			 * following eID authentication will fail.
			 */
			this.pkcs11Eid.close();
			if (null != logoffReaderName) {
				this.pcscEidSpi.logoff(logoffReaderName);
			}
		}

		AuthenticationDataMessage authenticationDataMessage = new AuthenticationDataMessage(
				salt, sessionId, signatureValue, authnCertChain);
		Object responseMessage = sendMessage(authenticationDataMessage);
		if (false == (responseMessage instanceof FinishedMessage)) {
			throw new RuntimeException("finish expected");
		}
	}

	private void performEidPcscAuthnOperation(byte[] salt, byte[] sessionId,
			byte[] toBeSigned, boolean logoff, boolean removeCard)
			throws Exception {
		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.DETECTING_CARD));
		if (false == this.pcscEidSpi.isEidPresent()) {
			setStatusMessage(Status.NORMAL, this.messages
					.getMessage(MESSAGE_ID.INSERT_CARD_QUESTION));
			this.pcscEidSpi.waitForEidPresent();
		}

		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.AUTHENTICATING));
		byte[] signatureValue;
		List<X509Certificate> authnCertChain;
		try {
			signatureValue = this.pcscEidSpi.signAuthn(toBeSigned);
			authnCertChain = this.pcscEidSpi.getAuthnCertificateChain();
			if (logoff && !removeCard) {
				this.pcscEidSpi.logoff();
			}
			if (removeCard) {
				setStatusMessage(Status.NORMAL, this.messages
						.getMessage(MESSAGE_ID.REMOVE_CARD));
				this.pcscEidSpi.removeCard();
			}
		} finally {
			this.pcscEidSpi.close();
		}

		AuthenticationDataMessage authenticationDataMessage = new AuthenticationDataMessage(
				salt, sessionId, signatureValue, authnCertChain);
		Object responseMessage = sendMessage(authenticationDataMessage);
		if (false == (responseMessage instanceof FinishedMessage)) {
			throw new RuntimeException("finish expected");
		}
	}

	public static final String VERSION = "0.0.11";

	private void printEnvironment() {
		addDetailMessage("eID browser applet version: " + VERSION);
		addDetailMessage("Java version: " + System.getProperty("java.version"));
		addDetailMessage("Java vendor: " + System.getProperty("java.vendor"));
		addDetailMessage("OS: " + System.getProperty("os.name"));
		addDetailMessage("OS version: " + System.getProperty("os.version"));
		addDetailMessage("OS arch: " + System.getProperty("os.arch"));
		addDetailMessage("Web application URL: "
				+ this.runtime.getDocumentBase());
		// TODO when using SunPKCS11 we only accept the Sun JRE
		// TODO can we assume there is only Sun JRE? OpenJDK variants...
	}

	public void addDetailMessage(String detailMessage) {
		this.view.addDetailMessage(detailMessage);
	}

	private int maxProgress;

	private int currentProgress;

	private void visualizeProgress() {
		this.currentProgress++;
		this.view.progressIndication(this.maxProgress, this.currentProgress);
	}

	private class PcscEidObserver implements Observer {

		public void update(Observable observable, Object arg) {
			Controller.this.visualizeProgress();
		}
	}

	private void performEidIdentificationOperation(boolean includeAddress,
			boolean includePhoto, boolean includeIntegrityData,
			boolean includeCertificates, boolean removeCard) throws Exception {
		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.DETECTING_CARD));
		if (false == this.pcscEidSpi.isEidPresent()) {
			setStatusMessage(Status.NORMAL, this.messages
					.getMessage(MESSAGE_ID.INSERT_CARD_QUESTION));
			this.pcscEidSpi.waitForEidPresent();
		}

		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.READING_IDENTITY));

		boolean response = this.view.privacyQuestion(includeAddress,
				includePhoto);
		if (false == response) {
			this.pcscEidSpi.close();
			throw new SecurityException(
					"user did not agree to release eID identity information");
		}

		addDetailMessage("Reading identity file...");

		/*
		 * Calculate the maximum progress bar indication
		 */
		this.maxProgress = 1; // identity file
		if (includeAddress) {
			this.maxProgress++;
		}
		if (includePhoto) {
			this.maxProgress += 3000 / 255;
		}
		if (includeIntegrityData) {
			this.maxProgress++; // identity signature file
			if (includeAddress) {
				this.maxProgress++; // address signature file
			}
			this.maxProgress += (1050 / 255) + 1; // RRN certificate file
			this.maxProgress += (1050 / 255) + 1; // Root certificate file
		}
		if (includeCertificates) {
			this.maxProgress += (1050 / 255) + 1; // authn cert file
			this.maxProgress += (1050 / 255) + 1; // sign cert file
			this.maxProgress += (1050 / 255) + 1; // citizen CA cert file
			if (false == includeIntegrityData) {
				this.maxProgress += (1050 / 255) + 1; // root CA cert file
			}
		}
		this.currentProgress = 0;
		this.view.progressIndication(this.maxProgress, this.currentProgress);

		TaskRunner taskRunner = new TaskRunner(this.pcscEidSpi, this.view);
		/*
		 * Next design pattern is the only way to handle the case where multiple
		 * application access the smart card at the same time.
		 */
		byte[] idFile = taskRunner.run(new Task<byte[]>() {
			public byte[] run() throws Exception {
				return Controller.this.pcscEidSpi
						.readFile(PcscEid.IDENTITY_FILE_ID);
			}
		});
		addDetailMessage("Size identity file: " + idFile.length);

		byte[] addressFile = null;
		if (includeAddress) {
			addDetailMessage("Read address file...");
			addressFile = taskRunner.run(new Task<byte[]>() {

				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.ADDRESS_FILE_ID);
				}
			});
			addDetailMessage("Size address file: " + addressFile.length);
		}

		byte[] photoFile = null;
		if (includePhoto) {
			addDetailMessage("Read photo file...");
			photoFile = taskRunner.run(new Task<byte[]>() {

				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.PHOTO_FILE_ID);
				}
			});
		}

		byte[] identitySignatureFile = null;
		byte[] addressSignatureFile = null;
		byte[] rrnCertFile = null;
		byte[] rootCertFile = null;
		if (includeIntegrityData) {
			addDetailMessage("Read identity signature file...");
			identitySignatureFile = taskRunner.run(new Task<byte[]>() {
				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.IDENTITY_SIGN_FILE_ID);
				}
			});
			if (includeAddress) {
				addDetailMessage("Read address signature file...");
				addressSignatureFile = taskRunner.run(new Task<byte[]>() {
					public byte[] run() throws Exception {
						return Controller.this.pcscEidSpi
								.readFile(PcscEid.ADDRESS_SIGN_FILE_ID);
					}
				});
			}
			addDetailMessage("Read national registry certificate file...");
			rrnCertFile = taskRunner.run(new Task<byte[]>() {
				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.RRN_CERT_FILE_ID);
				}
			});
			addDetailMessage("reading root certificate file...");
			rootCertFile = taskRunner.run(new Task<byte[]>() {
				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.ROOT_CERT_FILE_ID);
				}
			});
		}

		byte[] authnCertFile = null;
		byte[] signCertFile = null;
		byte[] caCertFile = null;
		if (includeCertificates) {
			addDetailMessage("reading authn certificate file...");
			authnCertFile = taskRunner.run(new Task<byte[]>() {
				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.AUTHN_CERT_FILE_ID);
				}
			});

			addDetailMessage("reading sign certificate file...");
			signCertFile = taskRunner.run(new Task<byte[]>() {
				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.SIGN_CERT_FILE_ID);
				}
			});

			addDetailMessage("reading citizen CA certificate file...");
			caCertFile = taskRunner.run(new Task<byte[]>() {
				public byte[] run() throws Exception {
					return Controller.this.pcscEidSpi
							.readFile(PcscEid.CA_CERT_FILE_ID);
				}
			});

			if (null == rootCertFile) {
				addDetailMessage("reading root certificate file...");
				rootCertFile = taskRunner.run(new Task<byte[]>() {
					public byte[] run() throws Exception {
						return Controller.this.pcscEidSpi
								.readFile(PcscEid.ROOT_CERT_FILE_ID);
					}
				});
			}
		}

		this.view.progressIndication(-1, 0);

		if (removeCard) {
			setStatusMessage(Status.NORMAL, this.messages
					.getMessage(MESSAGE_ID.REMOVE_CARD));
			this.pcscEidSpi.removeCard();
		}

		this.pcscEidSpi.close();

		setStatusMessage(Status.NORMAL, this.messages
				.getMessage(MESSAGE_ID.TRANSMITTING_IDENTITY));

		IdentityDataMessage identityData = new IdentityDataMessage(idFile,
				addressFile, photoFile, identitySignatureFile,
				addressSignatureFile, rrnCertFile, rootCertFile, authnCertFile,
				signCertFile, caCertFile);
		sendMessage(identityData, FinishedMessage.class);
	}

	public static final String APPLET_SERVICE_PARAM = "AppletService";

	private HttpURLConnection getServerConnection()
			throws MalformedURLException, IOException {
		String appletServiceParam = this.runtime
				.getParameter(APPLET_SERVICE_PARAM);
		if (null == appletServiceParam) {
			throw new IllegalArgumentException("no " + APPLET_SERVICE_PARAM
					+ " parameter specified");
		}

		URL appletServiceUrl = new URL(this.runtime.getDocumentBase(),
				appletServiceParam);

		/*
		 * Install our SSL socket factory.
		 */
		AppletSSLSocketFactory.installSocketFactory(this.view);

		HttpURLConnection connection = (HttpURLConnection) appletServiceUrl
				.openConnection();
		return connection;
	}

	private void setStatusMessage(Status status, String statusMessage) {
		this.view.setStatusMessage(status, statusMessage);
	}

	public Component getParentComponent() {
		return this.view.getParentComponent();
	}
}