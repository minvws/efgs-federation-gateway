/*-
 * ---license-start
 * EU-Federation-Gateway-Service / efgs-federation-gateway
 * ---
 * Copyright (C) 2020 T-Systems International GmbH and all other contributors
 * ---
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
 * ---license-end
 */

package eu.interop.federationgateway.config;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import com.googlecode.protobuf.format.ProtobufFormatter;
import eu.interop.federationgateway.utils.SemVerUtils;
import java.io.IOException;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal implementation of HttpMessageConverter to convert De- and Encode Protobuf messages.
 * But enables the usage the European Federation Gateway special Protobuf Content Type.
 * Inspired from {@link org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter}
 */
@Slf4j
@AllArgsConstructor
@Component
public class ProtobufHttpMessageConverter extends AbstractHttpMessageConverter<Message> {

  /**
   * The HTTP header containing the protobuf schema.
   */
  public static final String X_PROTOBUF_SCHEMA_HEADER = "X-Protobuf-Schema";
  /**
   * The HTTP header containing the protobuf message.
   */
  public static final String X_PROTOBUF_MESSAGE_HEADER = "X-Protobuf-Message";
  /**
   * The special versioned European Federation Gateway Content Type.
   */
  private static final MediaType PROTOBUF_MEDIA_TYPE = new MediaType("application", "protobuf");

  private static final MediaType JSON_MEDIA_TYPE = new MediaType("application", "json");

  private final EfgsProperties properties;

  @Override
  protected boolean supports(Class<?> clazz) {
    return Message.class.isAssignableFrom(clazz);
  }

  @Override
  protected MediaType getDefaultContentType(Message message) {
    return PROTOBUF_MEDIA_TYPE;
  }

  @Override
  public List<MediaType> getSupportedMediaTypes() {
    return List.of(PROTOBUF_MEDIA_TYPE, JSON_MEDIA_TYPE);
  }

  @Override
  protected Message readInternal(
    Class<? extends Message> clazz,
    HttpInputMessage httpInputMessage
  ) throws HttpMessageNotReadableException, IOException {
    MediaType contentType = httpInputMessage.getHeaders().getContentType();
    if (contentType == null) {
      log.error("Accept must be set");
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content Type must not be null!");
    }

    MediaType targetContentType = null;
    String targetContentTypeVersion = null;

    if (PROTOBUF_MEDIA_TYPE.isCompatibleWith(contentType)) {
      targetContentType = PROTOBUF_MEDIA_TYPE;
      targetContentTypeVersion = properties.getContentNegotiation().getProtobufVersion();
    } else if (JSON_MEDIA_TYPE.isCompatibleWith(contentType)) {
      targetContentType = JSON_MEDIA_TYPE;
      targetContentTypeVersion = properties.getContentNegotiation().getJsonVersion();
    }

    if (targetContentType == null) {
      log.error("Accepted Content-Type is not compatible\", requestedMediaType=\"{}", contentType);
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Content-Type!");
    }

    if (contentType.getParameter("version") == null) {
      log.error("Version parameter of Accepted Content-Type is required\", requestedMediaType=\"{}", contentType);
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version parameter of Content-Type is required!");
    }

    try {
      if (!SemVerUtils.parseSemVerAndCheckCompatibility(
        targetContentTypeVersion,
        contentType.getParameter("version")
      )) {
        log.error("Serialization: Protocol version is not compatible\", requestedMediaType=\"{}", contentType);
        throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "Protocol version is not compatible!");
      }
    } catch (SemVerUtils.SemVerParsingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    Message.Builder builder;

    try {
      builder = (Message.Builder) clazz.getMethod("newBuilder").invoke(clazz);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "Invalid Protobuf Message type: no invocable newBuilder() method on " + clazz);
    }

    if (targetContentType == PROTOBUF_MEDIA_TYPE) {
      return builder.mergeFrom(httpInputMessage.getBody()).build();
    } else if (targetContentType == JSON_MEDIA_TYPE) {
      ProtobufFormatter formatter = new ProtobufConverter();

      formatter.merge(httpInputMessage.getBody(), builder);
      return builder.build();
    }

    return null;
  }

  @Override
  protected void writeInternal(
    Message message,
    HttpOutputMessage httpOutputMessage
  ) throws IOException, HttpMessageNotWritableException {
    MediaType contentType = httpOutputMessage.getHeaders().getContentType();
    if (contentType == null) {
      log.error("Accept must be set");
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accept must be set!");
    }

    MediaType targetContentType = null;
    String targetContentTypeVersion = null;

    if (PROTOBUF_MEDIA_TYPE.isCompatibleWith(contentType)) {
      targetContentType = PROTOBUF_MEDIA_TYPE;
      targetContentTypeVersion = properties.getContentNegotiation().getProtobufVersion();
    } else if (JSON_MEDIA_TYPE.isCompatibleWith(contentType)) {
      targetContentType = JSON_MEDIA_TYPE;
      targetContentTypeVersion = properties.getContentNegotiation().getJsonVersion();
    }

    if (targetContentType == null) {
      log.error("Accepted Content-Type is not compatible\", requestedMediaType=\"{}", contentType);
      throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type is not compatible");
    }

    if (contentType.getParameter("version") == null) {
      log.error("Version parameter of Accepted Content-Type is required\", requestedMediaType=\"{}", contentType);
      throw new ResponseStatusException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "Version parameter of Content-Type is required!");
    }

    try {
      if (!SemVerUtils.parseSemVerAndCheckCompatibility(
        targetContentTypeVersion,
        contentType.getParameter("version")
      )) {
        log.error("Serialization: Protocol version is not compatible\", requestedMediaType=\"{}", contentType);
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Protocol version is not compatible!");
      }
    } catch (SemVerUtils.SemVerParsingException e) {
      throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    setProtoHeader(httpOutputMessage, message);
    if (targetContentType == PROTOBUF_MEDIA_TYPE) {
      CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(httpOutputMessage.getBody());
      message.writeTo(codedOutputStream);
      codedOutputStream.flush();
    } else if (targetContentType == JSON_MEDIA_TYPE) {
      ProtobufFormatter formatter = new ProtobufConverter();
      formatter.print(message, httpOutputMessage.getBody());
      httpOutputMessage.getBody().flush();
    }
  }

  private void setProtoHeader(HttpOutputMessage response, Message message) {
    response.getHeaders().set(X_PROTOBUF_SCHEMA_HEADER, message.getDescriptorForType().getFile().getName());
    response.getHeaders().set(X_PROTOBUF_MESSAGE_HEADER, message.getDescriptorForType().getFullName());
  }
}
