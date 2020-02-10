/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
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
 *
 */

var local;
var video;
var webRtcPeer;
var audioDetection;
var samples = [];
var sdpOffer;
var videoStream = null;
var audioStream = null;
var iceCandidates = [];
var iceServers;
var peerConnection;
var channel;

var defaultVideoConstraints = {
	width : {
		max : 640
	},
	frameRate : {
		min : 10,
		ideal : 15,
		max : 20
	}
};
var userMediaConstraints = {
	audio : true,
	video : defaultVideoConstraints
};

window.onload = function() {
	console = new Console("console", console);
	local = document.getElementById("local");
	video = document.getElementById("video");

	setInterval(updateCurrentTime, 100);
}

function setIceServers(url, username, credential) {
	if (username == "null" || credential == "null") {
		iceServers = [ {
			urls : url
		} ];
	} else {
		iceServers = [ {
			urls : url,
			username : username,
			credential : credential
		} ];
	}
}

function setAudioUserMediaConstraints() {
	userMediaConstraints = {
		audio : true,
		video : false
	};
}

function setVideoUserMediaConstraints() {
	userMediaConstraints = {
		audio : false,
		video : defaultVideoConstraints
	};
}

function setCustomAudio(audioUrl) {
	mediaConstraints = {
		audio : true,
		video : defaultVideoConstraints
	};
	getUserMedia(mediaConstraints, function(userStream) {
		videoStream = userStream;
	}, onError);

	var context = new AudioContext();
	var audioTest = document.getElementById("audioTest");
	audioTest.src = audioUrl;
	var sourceStream = context.createMediaElementSource(audioTest);
	var mixedOutput = context.createMediaStreamDestination();
	sourceStream.connect(mixedOutput);
	audioStream = mixedOutput.stream;
}

function useDataChannels() {
	var servers = null;
	var configuration = null;
	var dataConstraints = null;
	peerConnection = new RTCPeerConnection(servers, configuration);
	channel = peerConnection.createDataChannel("dataChannel", dataConstraints);

	channel.onopen = onSendChannelStateChange;
	channel.onclose = onSendChannelStateChange;
	channel.onmessage = onMessage;
}

function onSendChannelStateChange() {
	document.getElementById("datachannel-state").value = channel.readyState;
}

function onMessage(event) {
	document.getElementById("datachannel-received").value = event["data"];
}

function sendDataByChannel(message) {
	if (channel) {
		channel.send(message);
	}
}

function onIceCandidate(candidate) {
	const candidateStr = JSON.stringify(candidate);
	console.log(`Local candidate: '${candidateStr}'`);
	iceCandidates.push(candidateStr);
}

function startSendRecv() {
	console.log("Starting WebRTC in SendRecv mode...");
	showSpinner(local, video);

	var options = {
		localVideo : local,
		remoteVideo : video,
		mediaConstraints : userMediaConstraints,
		onicecandidate : onIceCandidate
	}

	if (iceServers) {
		options.configuration = {
			iceServers : iceServers
		};
	}

	if (peerConnection) {
		options.peerConnection = peerConnection;
	}
	console.log("Options:" + JSON.stringify(options));

	webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
			function(error) {
				if (error) {
					onError(error);
				}

				console.log("WebRtcPeerSendrecv created; start local video");
				startVideo(local);

				webRtcPeer.generateOffer(onOffer);
			});
}

function startSendOnly() {
	console.log("Starting WebRTC in SendOnly mode...");
	showSpinner(local);

	var options = {
		localVideo : local,
		mediaConstraints : userMediaConstraints,
		onicecandidate : onIceCandidate
	}

	if (iceServers) {
		options.configuration = {
			iceServers : iceServers
		};
	}

	if (peerConnection) {
		options.peerConnection = peerConnection;
	}
	console.log("Options:" + JSON.stringify(options));

	webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendonly(options,
			function(error) {
				if (error) {
					onError(error);
				}

				console.log("WebRtcPeerSendonly created; start local video");
				startVideo(local);

				webRtcPeer.generateOffer(onOffer);
			});
}

function startRecvOnly() {
	console.log("Starting WebRTC in RecvOnly mode...");
	showSpinner(video);

	var options = {
		remoteVideo : video,
		mediaConstraints : userMediaConstraints,
		onicecandidate : onIceCandidate
	}

	if (iceServers) {
		options.configuration = {
			iceServers : iceServers
		};
	}

	if (peerConnection) {
		options.peerConnection = peerConnection;
	}
	console.log("Options:" + JSON.stringify(options));

	webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerRecvonly(options,
			function(error) {
				if (error) {
					onError(error);
				}

				console.log("WebRtcPeerRecvonly created; start local video");
				startVideo(local);

				webRtcPeer.generateOffer(onOffer);
			});
}

function onError(error) {
	console.error(error);
}

function onOffer(error, offer) {
	console.info("SDP Offer:", offer);
	sdpOffer = offer;
}

function addIceCandidate(serverCandidate) {
	candidate = JSON.parse(serverCandidate);
	webRtcPeer.addIceCandidate(candidate, function(error) {
		if (error) {
			console.error("Error adding candidate: " + error);
			return;
		}
	});
}

function processSdpAnswer(answer) {
	var sdpAnswer = window.atob(answer);
	console.info("SDP answer:", sdpAnswer);

	webRtcPeer.processAnswer(sdpAnswer, function(error) {
		if (error) {
			return console.error(error);
		}

		console.log("SDP Answer ready; start remote video");
    	startVideo(video);
	});
}

function startVideo(videoTag) {
	// Manually start the <video> HTML element. This is used instead of the
	// "autoplay" attribute, to work with newer browser security policies.
	// Ref: https://developer.mozilla.org/en-US/docs/Web/HTML/Element/video
	videoTag.play().catch(err => {
		if (err.name === "NotAllowedError") {
			console.error("Browser doesn't allow playing video: " + err);
		} else {
			console.error("Error in video.play(): " + err);
		}
	});
}

function activateAudioDetection() {
	var pc = webRtcPeer.peerConnection;
	var wrStream = pc.getRemoteStreams()[0];
	audioDetection = kurentoUtils.WebRtcPeer.hark(wrStream);
	audioDetection.on('speaking', function() {
	})
	audioDetection.on('volume_change', function(volume, threshold) {
		if (volume == "-Infinity") {
			volume = 0;
		}
		samples.push(volume);
	})
}

function checkAudioDetection() {
	var count = 0;
	for (var s in samples) {
		if (samples[s] < 0) {
			if (count < 20)
				count = 0;
		} else {
			count++;
		}
	}
	return (count < 20);
}

function stopAudioDetection() {
	if (audioDetection != undefined) {
		audioDetection.stop();
	}
}

function initAudioDetection() {
	samples = [];
}

function updateCurrentTime() {
	// "video.currentTime" is the current playback position, in seconds
	// https://developer.mozilla.org/en-US/docs/Web/HTML/Element/video#attr-currentTime
	document.getElementById("currentTime").value = video.currentTime;
}

function addEventListener(type, callback) {
	video.addEventListener(type, callback, false);
}

function addTestName(testName) {
	document.getElementById("testName").innerHTML = testName;
}

function appendStringToTitle(string) {
	document.getElementById("testTitle").innerHTML += " " + string;
}

function showSpinner() {
	for (var i = 0; i < arguments.length; i++) {
		arguments[i].poster = './img/transparent-1px.png';
		arguments[i].style.background = "center transparent url('./img/spinner.gif') no-repeat";
	}
}

function hideSpinner() {
	for (var i = 0; i < arguments.length; i++) {
		arguments[i].src = '';
		arguments[i].poster = './img/webrtc.png';
		arguments[i].style.background = '';
	}
}

function stop() {
	if (webRtcPeer) {
		webRtcPeer.dispose();
		webRtcPeer = null;
	}
	hideSpinner(local, video);
	document.getElementById('status').value = '';
}
