package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;

record DocumentUploadPublication(Document document, IngestJob job) { }
