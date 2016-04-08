/*
The MIT License (MIT)

Copyright (c) 2014 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:
* 2015 creation copied from http://plindenbaum.blogspot.fr/2010/11/blastxmlannotations.html

*/
package com.github.lindenb.jvarkit.tools.biostar;

import gov.nih.nlm.ncbi.blast.Hit;
import htsjdk.samtools.util.IOUtil;

import java.io.PrintStream;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;


public class Biostar160470 extends AbstractBiostar160470
	{
	@SuppressWarnings("unused")
	private static final gov.nih.nlm.ncbi.blast.ObjectFactory _fool_javac1=null;
	private String blastBinDir=null;
	private String blastDb=null;
	
	
	/** XML input factory */
	private XMLInputFactory xif;
	/** transforms XML/DOM  */
	private Unmarshaller unmarshaller;
	/** transforms XML/DOM  */
	private Marshaller marshaller;
	

	/** parses BLAST output */
	private void parseBlast(final XMLEventReader r)  throws Exception
		{
		final PrintStream out=super.openFileOrStdoutAsPrintStream();
		final XMLOutputFactory xof = XMLOutputFactory.newFactory();
		final XMLEventWriter w=xof.createXMLEventWriter(out, "UTF-8");

		while(r.hasNext())
			{
			final XMLEvent evt=r.peek();
			if(evt.isStartElement() &&
					evt.asStartElement().getName().getLocalPart().equals("BlastOutput_program"))
				{
				w.add(r.nextEvent());//consumme
				final String BlastOutput_program = r.getElementText();
				if(!"tblastn".equals(BlastOutput_program))
					{
					throw new IOException("only tblastn is supported but got : "+BlastOutput_program);
					}
				}
			else if(evt.isStartElement() &&
				evt.asStartElement().getName().getLocalPart().equals("Hit"))
				{
				final Hit hit= this.unmarshaller.unmarshal(r, Hit.class).getValue();
				parseHit(out,hit);
				}
			else
				{
				w.add(r.nextEvent());//consumme
				}
			}
		
		w.flush();
		w.close();
		out.flush();
		out.close();
		}
	
	private void parseHit(final PrintStream out,Hit hit) throws Exception
		{
		// Create the Document
		final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		final DocumentBuilder db = dbf.newDocumentBuilder();
		final Document document = db.newDocument();
        
        // Marshal the Object to a Document
        marshaller.marshal(hit, document);

        @SuppressWarnings("unused")
		String hitDef=null;
    	String hitId=null;
    	final Element hitNode= document.getDocumentElement();
        for(Node c1=hitNode.getFirstChild();c1!=null;c1=c1.getNextSibling())
        	{
        	
        	if(c1.getNodeType()!=Node.ELEMENT_NODE) continue;
        	if(c1.getNodeName().equals("Hit_def"))
        		{
        		hitDef=c1.getTextContent().trim();
        		}
        	else if(c1.getNodeName().equals("Hit_id"))
	    		{
        		hitId=c1.getTextContent().trim();
	    		}
        	else if(c1.getNodeName().equals("Hit_hsps"))
	    		{
        		for(Node c2=c1.getFirstChild();c2!=null && hitId!=null;c2=c2.getNextSibling())
             		{
        			if(c2.getNodeType()!=Node.ELEMENT_NODE) continue;
                	if(c2.getNodeName().equals("Hsp"))
                		{
                		int hit_from=0;
                		int hit_to=0;
                		String Hsp_hseq=null;
                		for(Node c3=c2.getFirstChild();c3!=null;c3=c3.getNextSibling())
                 			{
                			if(c3.getNodeType()!=Node.ELEMENT_NODE) continue;
                        	if(c3.getNodeName().equals("Hsp_hit-from"))
                        		{
                        		hit_from = Integer.parseInt(c3.getTextContent().trim());
                        		}
                        	else if(c3.getNodeName().equals("Hsp_hit-to"))
	                    		{
                        		hit_to = Integer.parseInt(c3.getTextContent().trim());
	                    		}
                        	else if(c3.getNodeName().equals("Hsp_hseq"))
	                    		{
                        		Hsp_hseq = c3.getTextContent().trim();
	                    		}
                        	}
                		if(hit_from>hit_to) throw new RuntimeException("not handled");
                		final  List<String> args=new ArrayList<>();
                		args.add((this.blastBinDir==null?"":this.blastBinDir+File.separatorChar)+"blastdbcmd");
                		args.add("-db");
                		args.add(this.blastDb);
                		args.add("-entry");
                		args.add(hitId);
                		args.add("-outfmt");
                		args.add("%s");
                		args.add("-range");
                		args.add(String.valueOf(hit_from)+"-"+hit_to);
                		final ProcessBuilder proc=new ProcessBuilder(args);
                		final Process process = proc.start();
                		final StringBuilder sequence= new StringBuilder( IOUtil.readFully(process.getInputStream()));
                		if(process.waitFor()!=0)
                			{
                			throw new RuntimeException("Proc failed: "+args);
                			}
                		final Element hitDna=c2.getOwnerDocument().createElement("Hsp_hit-DNA");
                		for(int i=0;i< Hsp_hseq.length() && i*3+2< sequence.length();++i)
                			{
                			char c=Hsp_hseq.charAt(i);
                			if(Character.isLetter(c)) continue;
                			sequence.setCharAt(i*3+0,c);
                			sequence.setCharAt(i*3+1,c);
                			sequence.setCharAt(i*3+2,c);                			
                			}
                		hitDna.appendChild(c2.getOwnerDocument().createTextNode(sequence.toString()));
                		c2.appendChild(hitDna);
                		}
             		}
	    		}
    	
        	}
        
        
        
        
        out.flush();
     // Output the Document
        final  TransformerFactory tf = TransformerFactory.newInstance();
        final  Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");
        t.setOutputProperty(OutputKeys.INDENT,"yes");
        final  DOMSource source = new DOMSource(document);
        final StreamResult result = new StreamResult(System.out);
        t.transform(source, result);			
		}
		
	@Override
	public Collection<Throwable> call(final String filename) throws Exception {
		if(blastDb==null)
			{
			return wrapException("undefined blastdb");
			}
		XMLEventReader r=null;
		try {
			//create a Unmarshaller for genbank
			final JAXBContext jc = JAXBContext.newInstance("gov.nih.nlm.ncbi.blast");
			this.unmarshaller=jc.createUnmarshaller();
			this.marshaller=jc.createMarshaller();
			this.marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
			this.marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
	
			this.xif=XMLInputFactory.newFactory();
			this.xif.setXMLResolver(new XMLResolver()
				{
				@Override
				public Object resolveEntity(String publicID,
						String systemID, String baseURI, String namespace)
						throws XMLStreamException {
							return new ByteArrayInputStream(new byte[0]);
						}
				});
			
			//read from stdin
			if(filename==null)
				{
			    r=this.xif.createXMLEventReader(stdin(), "UTF-8");
				this.parseBlast(r);
				r.close();
				r=null;
				}
			else
				{
				final FileReader fr=new java.io.FileReader(filename);
				r=this.xif.createXMLEventReader(fr);
				this.parseBlast(r);
				r.close();
				fr.close();
				r=null;
				}
			return RETURN_OK;
		} catch (final Exception e) {
			return wrapException(e);
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args)
		{
		new Biostar160470().instanceMainWithExit(args);
		}

	}
